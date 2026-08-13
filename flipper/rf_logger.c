/*
 * rf_logger.c — Sub-GHz RSSI logger for Flipper Zero
 *
 * CSV over USB CDC ch1 (ch0 stays as CLI). Takes over the USB stack with
 * dual-CDC when entering Running state, restores on exit.
 *
 * The same CSV lines are also streamed over BLE: at launch the app swaps
 * the BLE stack to its own Serial profile instance (same GATT service the
 * stock CLI-over-BLE uses, so phone-side code is identical for both) and
 * transmits every sample to the connected central. Default profile is
 * restored on exit. Requires Bluetooth ON in Flipper settings to advertise.
 */

#include <furi.h>
#include <furi_hal.h>
#include <furi_hal_subghz.h>
#include <furi_hal_usb.h>
#include <furi_hal_usb_cdc.h>
#include <furi_hal_bt.h>
#include <gui/gui.h>
#include <gui/elements.h>
#include <input/input.h>
#include <storage/storage.h>
#include <notification/notification_messages.h>
#include <bt/bt_service/bt.h>
#include <profiles/serial_profile.h>
#include <lib/subghz/devices/cc1101_configs.h>

#define TAG               "rf_logger"
#define SAMPLE_PERIOD_MS  200u
#define VCP_DATA_CH       1u
#define LOG_DIR           EXT_PATH("apps_data/rf_logger")

typedef enum { StateManualEntry, StateRunning } AppState;

// Manual entry: edit a XXX.XX MHz value digit by digit.
// 5 editable positions: [0]=100s [1]=10s [2]=1s . [3]=0.1 [4]=0.01
#define MANUAL_DIGITS 5
static const uint32_t MANUAL_DIGIT_HZ[MANUAL_DIGITS] = {
    100000000u, // hundreds of MHz
     10000000u, // tens of MHz
      1000000u, // ones of MHz
       100000u, // 0.1 MHz
        10000u, // 0.01 MHz
};
#define MANUAL_MIN_HZ 300000000u
#define MANUAL_MAX_HZ 928000000u

typedef struct {
    AppState state;
    uint32_t freq_req_hz;
    uint32_t freq_act_hz;
    uint32_t manual_hz;      // current editable frequency in Hz
    uint8_t  manual_cursor;  // 0..MANUAL_DIGITS-1
    int rssi_dbm;
    uint8_t rssi_raw;
    uint8_t lqi;
    uint32_t n;
    bool sd_logging;
    File* log_file;
    FuriMutex* mutex;
    Gui* gui;
    ViewPort* viewport;
    FuriMessageQueue* input_queue;
    NotificationApp* notifications;
    Storage* storage;
    FuriHalUsbInterface* prev_usb;
    bool usb_taken;
    Bt* bt;
    FuriHalBleProfileBase* ble_profile;
    volatile BtStatus bt_status;
    uint32_t next_adv_kick;
    uint32_t next_profile_retry;
} RfLoggerApp;

/* ---- BLE serial streaming ---- */

static void bt_status_cb(BtStatus status, void* ctx) {
    RfLoggerApp* app = ctx;
    app->bt_status = status;
}

static void ble_start(RfLoggerApp* app) {
    app->bt = furi_record_open(RECORD_BT);
    bt_disconnect(app->bt);
    furi_delay_ms(200); // let the stack settle before the profile swap
    app->ble_profile = bt_profile_start(app->bt, ble_profile_serial, NULL);
    if(app->ble_profile) {
        bt_set_status_changed_callback(app->bt, bt_status_cb, app);
        furi_hal_bt_start_advertising();
    }
}

/* Advertising is not fire-and-forget: a settings toggle, key wipe
 * ("Forget All Paired Devices") or stack restart silently kills it.
 * Called from the main loop — re-kicks advertising while not connected,
 * and retries the profile swap if it failed at launch (e.g. BT was off). */
static void ble_keepalive(RfLoggerApp* app) {
    uint32_t now = furi_get_tick();
    if(!app->bt) return;
    if(!app->ble_profile) {
        if(now >= app->next_profile_retry) {
            app->next_profile_retry = now + 10000;
            app->ble_profile = bt_profile_start(app->bt, ble_profile_serial, NULL);
            if(app->ble_profile) {
                bt_set_status_changed_callback(app->bt, bt_status_cb, app);
                furi_hal_bt_start_advertising();
            }
        }
        return;
    }
    if(now >= app->next_adv_kick) {
        app->next_adv_kick = now + 2000;
        if(app->bt_status != BtStatusConnected) furi_hal_bt_start_advertising();
    }
}

static void ble_stop(RfLoggerApp* app) {
    if(!app->bt) return;
    bt_set_status_changed_callback(app->bt, NULL, NULL);
    bt_disconnect(app->bt);
    furi_delay_ms(200);
    bt_profile_restore_default(app->bt);
    furi_record_close(RECORD_BT);
    app->bt = NULL;
    app->ble_profile = NULL;
}

static void usb_take(RfLoggerApp* app) {
    if(app->usb_taken) return;
    app->prev_usb = furi_hal_usb_get_config();
    furi_hal_usb_unlock();
    if(furi_hal_usb_set_config(&usb_cdc_dual, NULL)) app->usb_taken = true;
}

static void usb_release(RfLoggerApp* app) {
    if(!app->usb_taken) return;
    furi_hal_usb_set_config(app->prev_usb, NULL);
    app->usb_taken = false;
}

static void cdc_write(const char* s) {
    if(s) furi_hal_cdc_send(VCP_DATA_CH, (uint8_t*)s, (uint16_t)strlen(s));
}

static void cdc_printf(const char* fmt, ...) {
    char buf[160];
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if(n > 0) furi_hal_cdc_send(VCP_DATA_CH, (uint8_t*)buf, (uint16_t)n);
}

static void log_open(RfLoggerApp* app) {
    storage_simply_mkdir(app->storage, LOG_DIR);
    char path[96];
    snprintf(path, sizeof(path), LOG_DIR "/log_%lu.csv", (unsigned long)furi_get_tick());
    app->log_file = storage_file_alloc(app->storage);
    if(!storage_file_open(app->log_file, path, FSAM_WRITE, FSOM_CREATE_ALWAYS)) {
        storage_file_free(app->log_file); app->log_file = NULL; app->sd_logging = false; return;
    }
    const char* h = "ts_ms,req_hz,act_hz,rssi_dbm,rssi_raw,lqi,n\n";
    storage_file_write(app->log_file, h, strlen(h));
}

static void log_close(RfLoggerApp* app) {
    if(app->log_file) { storage_file_close(app->log_file); storage_file_free(app->log_file); app->log_file = NULL; }
}

static void log_write_line(RfLoggerApp* app, const char* line) {
    if(app->log_file) storage_file_write(app->log_file, line, strlen(line));
}

static bool subghz_running = false;

static bool subghz_retune(RfLoggerApp* app, uint32_t hz) {
    if(!furi_hal_subghz_is_frequency_valid(hz)) return false;
    furi_hal_subghz_idle();
    furi_hal_subghz_reset();
    furi_hal_subghz_load_custom_preset(subghz_device_cc1101_preset_ook_650khz_async_regs);
    app->freq_req_hz = hz;
    app->freq_act_hz = furi_hal_subghz_set_frequency_and_path(hz);
    app->n = 0;
    furi_hal_subghz_rx();
    cdc_printf("# RF_LOGGER_DBG req=%lu act=%lu\r\n",
               (unsigned long)hz, (unsigned long)app->freq_act_hz);
    cdc_write("ts_ms,req_hz,act_hz,rssi_dbm,rssi_raw,lqi,n\r\n");
    return true;
}

static bool subghz_start(RfLoggerApp* app, uint32_t hz) {
    if(!subghz_running) {
        furi_hal_power_suppress_charge_enter();
        subghz_running = true;
    }
    return subghz_retune(app, hz);
}

static void subghz_stop(void) {
    if(!subghz_running) return;
    furi_hal_subghz_idle();
    furi_hal_subghz_set_path(FuriHalSubGhzPathIsolate);
    furi_hal_subghz_sleep();
    furi_hal_power_suppress_charge_exit();
    subghz_running = false;
}

static void sample_once(RfLoggerApp* app) {
    float rssi_f = furi_hal_subghz_get_rssi();
    app->rssi_dbm = (int)rssi_f;
    int raw = (int)(rssi_f + 128.0f);
    if(raw < 0) raw = 0; else if(raw > 255) raw = 255;
    app->rssi_raw = (uint8_t)raw;
    app->lqi = furi_hal_subghz_get_lqi();
    app->n++;
    char line[128];
    int len = snprintf(line, sizeof(line),
                       "%lu,%lu,%lu,%d,0x%02X,%u,%lu\r\n",
                       (unsigned long)furi_get_tick(),
                       (unsigned long)app->freq_req_hz,
                       (unsigned long)app->freq_act_hz,
                       app->rssi_dbm, app->rssi_raw,
                       (unsigned)app->lqi, (unsigned long)app->n);
    if(len > 0) {
        furi_hal_cdc_send(VCP_DATA_CH, (uint8_t*)line, (uint16_t)len);
        /* Don't gate BLE TX on a cached status enum — that callback proved
         * unreliable for a custom profile, silently starving the phone.
         * The serial profile itself returns false when there's no subscriber,
         * so an unconditional call is safe and self-correcting. */
        if(app->ble_profile)
            ble_profile_serial_tx(app->ble_profile, (uint8_t*)line, (uint16_t)len);
        if(app->sd_logging) log_write_line(app, line);
    }
}


static void draw_manual(Canvas* canvas, RfLoggerApp* app) {
    canvas_clear(canvas);
    canvas_set_font(canvas, FontPrimary);
    canvas_draw_str(canvas, 4, 12, "Manual Frequency");

    // Build the 5 digits string of XXX.XX (no dot, dot rendered separately)
    char digits[6];
    uint32_t hz = app->manual_hz;
    digits[0] = '0' + (char)((hz / 100000000u) % 10u);
    digits[1] = '0' + (char)((hz /  10000000u) % 10u);
    digits[2] = '0' + (char)((hz /   1000000u) % 10u);
    digits[3] = '0' + (char)((hz /    100000u) % 10u);
    digits[4] = '0' + (char)((hz /     10000u) % 10u);
    digits[5] = '\0';

    canvas_set_font(canvas, FontBigNumbers);
    const int x0 = 16;
    const int y_text = 38;
    const int digit_w = 10;
    int x_positions[MANUAL_DIGITS];
    int x = x0;
    for(uint8_t i = 0; i < MANUAL_DIGITS; i++) {
        if(i == 3) x += 6; // gap for the decimal dot
        x_positions[i] = x;
        char one[2] = { digits[i], '\0' };
        canvas_draw_str(canvas, x, y_text, one);
        x += digit_w;
    }
    // Decimal point between [2] and [3]
    canvas_draw_str(canvas, x_positions[2] + digit_w, y_text, ".");
    // "MHz" suffix
    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, x + 2, y_text, "MHz");

    // Underline the active digit
    int ux = x_positions[app->manual_cursor];
    canvas_draw_line(canvas, ux, y_text + 2, ux + digit_w - 2, y_text + 2);

    // Hint footer
    canvas_draw_str(canvas, 4, 56, "Up/Dn change   L/R move");
    canvas_draw_str(canvas, 4, 64, "OK start   Back cancel");
}

static void manual_clamp(RfLoggerApp* app) {
    if(app->manual_hz < MANUAL_MIN_HZ) app->manual_hz = MANUAL_MIN_HZ;
    if(app->manual_hz > MANUAL_MAX_HZ) app->manual_hz = MANUAL_MAX_HZ;
}

static void manual_adjust(RfLoggerApp* app, int sign) {
    // Roll the single digit at the cursor 0..9 without overflowing neighbours.
    uint32_t step = MANUAL_DIGIT_HZ[app->manual_cursor];
    uint32_t cur = (app->manual_hz / step) % 10u;
    uint32_t next = (sign > 0) ? ((cur + 1u) % 10u) : ((cur + 9u) % 10u);
    app->manual_hz = app->manual_hz - cur * step + next * step;
    manual_clamp(app);
}

static void draw_signal_bar(Canvas* canvas, int x, int y, int w, int h, int rssi_dbm) {
    int span = 90;
    int v = rssi_dbm + 120;
    if(v < 0) v = 0;
    if(v > span) v = span;
    int fill = (v * w) / span;
    canvas_draw_frame(canvas, x, y, w, h);
    if(fill > 2) canvas_draw_box(canvas, x + 1, y + 1, fill - 2, h - 2);
}

static void draw_running(Canvas* canvas, RfLoggerApp* app) {
    canvas_clear(canvas);
    canvas_set_font(canvas, FontPrimary);
    char freq[24];
    snprintf(freq, sizeof(freq), "%lu.%02lu MHz",
             (unsigned long)(app->freq_act_hz / 1000000u),
             (unsigned long)((app->freq_act_hz / 10000u) % 100u));
    canvas_draw_str(canvas, 4, 12, freq);

    canvas_set_font(canvas, FontBigNumbers);
    char rssi[12];
    snprintf(rssi, sizeof(rssi), "%d", app->rssi_dbm);
    canvas_draw_str(canvas, 4, 38, rssi);
    canvas_set_font(canvas, FontSecondary);
    canvas_draw_str(canvas, 60, 38, "dBm");

    draw_signal_bar(canvas, 4, 44, 120, 8, app->rssi_dbm);

    const char* bt_s = app->bt_status == BtStatusConnected    ? "BT:ok" :
                       app->bt_status == BtStatusAdvertising ? "BT:adv" :
                                                               "BT:off";
    char info[48];
    snprintf(info, sizeof(info), "n=%lu lqi=%u %s %s",
             (unsigned long)app->n, (unsigned)app->lqi,
             app->sd_logging ? "SD:on" : "SD:off", bt_s);
    canvas_draw_str(canvas, 4, 62, info);
}

static void render_cb(Canvas* canvas, void* ctx) {
    RfLoggerApp* app = ctx;
    furi_mutex_acquire(app->mutex, FuriWaitForever);
    if(app->state == StateManualEntry) draw_manual(canvas, app);
    else draw_running(canvas, app);
    furi_mutex_release(app->mutex);
}

static void input_cb(InputEvent* ev, void* ctx) {
    RfLoggerApp* app = ctx;
    furi_message_queue_put(app->input_queue, ev, 0);
}

static void start_running(RfLoggerApp* app, uint32_t hz) {
    app->freq_req_hz = hz;
    app->n = 0;
    usb_take(app);
    if(subghz_start(app, hz)) {
        app->state = StateRunning;
        notification_message(app->notifications, &sequence_blink_start_green);
    }
}


static void handle_manual_input(RfLoggerApp* app, InputEvent* ev) {
    bool is_short  = (ev->type == InputTypeShort);
    bool is_repeat = (ev->type == InputTypeRepeat);
    if(!is_short && !is_repeat) return;
    switch(ev->key) {
    case InputKeyUp:    manual_adjust(app, +1); break;
    case InputKeyDown:  manual_adjust(app, -1); break;
    case InputKeyLeft:
        if(is_short && app->manual_cursor > 0) app->manual_cursor--;
        break;
    case InputKeyRight:
        if(is_short && app->manual_cursor + 1 < MANUAL_DIGITS) app->manual_cursor++;
        break;
    case InputKeyOk:
        if(is_short) {
            manual_clamp(app);
            if(furi_hal_subghz_is_frequency_valid(app->manual_hz)) {
                start_running(app, app->manual_hz);
            }
        }
        break;
    default: break;
    }
}

static void handle_running_input(RfLoggerApp* app, InputEvent* ev) {
    if(ev->type != InputTypeShort) return;
    switch(ev->key) {
    case InputKeyOk:
        app->sd_logging = !app->sd_logging;
        if(app->sd_logging) log_open(app); else log_close(app);
        break;
    case InputKeyBack:
        subghz_stop(); log_close(app);
        notification_message(app->notifications, &sequence_blink_stop);
        app->state = StateManualEntry;
        break;
    default: break;
    }
}

int32_t rf_logger_app(void* p) {
    UNUSED(p);
    RfLoggerApp* app = malloc(sizeof(RfLoggerApp));
    memset(app, 0, sizeof(*app));
    app->state = StateManualEntry;
    app->manual_hz = 433920000u; // sensible default inside the valid range
    app->manual_cursor = 2; // start on the ones-of-MHz digit
    app->mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    app->input_queue = furi_message_queue_alloc(8, sizeof(InputEvent));
    app->viewport = view_port_alloc();
    app->gui = furi_record_open(RECORD_GUI);
    app->storage = furi_record_open(RECORD_STORAGE);
    app->notifications = furi_record_open(RECORD_NOTIFICATION);

    view_port_draw_callback_set(app->viewport, render_cb, app);
    view_port_input_callback_set(app->viewport, input_cb, app);
    gui_add_view_port(app->gui, app->viewport, GuiLayerFullscreen);

    ble_start(app);

    bool exit = false;
    uint32_t next_sample = 0;
    while(!exit) {
        InputEvent ev;
        FuriStatus s = furi_message_queue_get(app->input_queue, &ev, 50);
        if(s == FuriStatusOk) {
            furi_mutex_acquire(app->mutex, FuriWaitForever);
            if(app->state == StateManualEntry) {
                if(ev.key == InputKeyBack && ev.type == InputTypeShort) exit = true;
                else handle_manual_input(app, &ev);
            } else {
                handle_running_input(app, &ev);
            }
            furi_mutex_release(app->mutex);
        }
        if(app->state == StateRunning) {
            uint32_t now = furi_get_tick();
            if(now >= next_sample) {
                furi_mutex_acquire(app->mutex, FuriWaitForever);
                sample_once(app);
                furi_mutex_release(app->mutex);
                next_sample = now + SAMPLE_PERIOD_MS;
            }
        }
        ble_keepalive(app);
        view_port_update(app->viewport);
    }

    subghz_stop();
    log_close(app);
    usb_release(app);
    ble_stop(app);
    gui_remove_view_port(app->gui, app->viewport);
    view_port_free(app->viewport);
    furi_message_queue_free(app->input_queue);
    furi_mutex_free(app->mutex);
    furi_record_close(RECORD_NOTIFICATION);
    furi_record_close(RECORD_STORAGE);
    furi_record_close(RECORD_GUI);
    free(app);
    return 0;
}
