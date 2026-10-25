use crate::dsp::{HermiteFractionalDelayBuffer, PsychoacousticDspPipeline, SAMPLE_RATE};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WasapiEndpointInfo {
    pub id: String,
    pub name: String,
    pub description: String,
    pub is_default: bool,
    pub is_headphone: bool,
    pub is_selected: bool,
    pub relative_gain: f32,
}

pub struct SharedAudioEngineState {
    pub dsp: PsychoacousticDspPipeline,
    pub selected_device_ids: HashSet<String>,
    pub device_relative_gains: HashMap<String, f32>,
    pub original_physical_default_id: Option<String>,
    pub dolby_virtual_endpoint_id: Option<String>,
    pub routing_dirty: bool,
    pub master_switch_dirty: bool,
}

impl SharedAudioEngineState {
    pub fn new() -> Self {
        Self {
            dsp: PsychoacousticDspPipeline::new(),
            selected_device_ids: HashSet::new(),
            device_relative_gains: HashMap::new(),
            original_physical_default_id: None,
            dolby_virtual_endpoint_id: None,
            routing_dirty: true,
            master_switch_dirty: true,
        }
    }
}

#[cfg(windows)]
mod win_wasapi {
    use super::*;
    use crate::policy_config::win_policy::{
        set_default_audio_endpoint, set_endpoint_tray_visibility,
        try_register_bundled_virtual_driver,
    };
    use std::ptr;
    use std::slice;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::thread;
    use std::time::{Duration, Instant};
    use tauri::{AppHandle, Emitter};
    use windows::core::{w, GUID, HRESULT, PCWSTR, PWSTR};
    use windows::Win32::Devices::FunctionDiscovery::{
        PKEY_Device_DeviceDesc, PKEY_Device_FriendlyName,
    };
    use windows::Win32::Foundation::{CloseHandle, HANDLE};
    use windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume;
    use windows::Win32::Media::Audio::{
        eConsole, eRender, EDataFlow, ERole, IAudioCaptureClient, IAudioClient, IAudioClock,
        IAudioRenderClient, IMMDevice, IMMDeviceEnumerator, IMMNotificationClient,
        MMDeviceEnumerator, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
        AUDCLNT_STREAMFLAGS_EVENTCALLBACK, AUDCLNT_STREAMFLAGS_LOOPBACK,
        AUDCLNT_STREAMFLAGS_NOPERSIST, AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, DEVICE_STATE,
        DEVICE_STATE_ACTIVE, WAVEFORMATEX, WAVEFORMATEXTENSIBLE,
    };
    use windows::Win32::Media::KernelStreaming::WAVE_FORMAT_EXTENSIBLE;
    use windows::Win32::Media::Multimedia::KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
    use windows::Win32::System::Com::StructuredStorage::PropVariantToStringAlloc;
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoTaskMemFree, CLSCTX_ALL, COINIT_MULTITHREADED,
        STGM_READ,
    };
    use windows::Win32::System::Threading::{
        AvSetMmThreadCharacteristicsW, CreateEventW, WaitForSingleObject,
    };
    use windows::Win32::UI::Shell::PropertiesSystem::PROPERTYKEY;

    // =========================================================================
    // Manual #[repr(C)] COM VTable Implementation for IMMNotificationClient
    // =========================================================================
    const IID_IUNKNOWN: GUID = GUID::from_u128(0x00000000_0000_0000_c000_000000000046);
    const IID_IMMNOTIFICATION_CLIENT: GUID =
        GUID::from_u128(0x7991eec9_7e89_4d85_8390_6c703cec60c0);

    #[repr(C)]
    struct RawNotificationClientVtbl {
        query_interface: unsafe extern "system" fn(
            this: *mut RawNotificationClient,
            riid: *const GUID,
            ppv: *mut *mut core::ffi::c_void,
        ) -> HRESULT,
        add_ref: unsafe extern "system" fn(this: *mut RawNotificationClient) -> u32,
        release: unsafe extern "system" fn(this: *mut RawNotificationClient) -> u32,
        on_device_state_changed: unsafe extern "system" fn(
            this: *mut RawNotificationClient,
            device_id: PCWSTR,
            new_state: DEVICE_STATE,
        ) -> HRESULT,
        on_device_added: unsafe extern "system" fn(
            this: *mut RawNotificationClient,
            device_id: PCWSTR,
        ) -> HRESULT,
        on_device_removed: unsafe extern "system" fn(
            this: *mut RawNotificationClient,
            device_id: PCWSTR,
        ) -> HRESULT,
        on_default_device_changed: unsafe extern "system" fn(
            this: *mut RawNotificationClient,
            flow: EDataFlow,
            role: ERole,
            default_device_id: PCWSTR,
        ) -> HRESULT,
        on_property_value_changed: unsafe extern "system" fn(
            this: *mut RawNotificationClient,
            device_id: PCWSTR,
            key: PROPERTYKEY,
        ) -> HRESULT,
    }

    #[repr(C)]
    struct RawNotificationClient {
        vtbl: *const RawNotificationClientVtbl,
        ref_count: AtomicU32,
        app_handle: AppHandle,
        shared_state: Arc<Mutex<SharedAudioEngineState>>,
    }

    static NOTIFICATION_VTBL: RawNotificationClientVtbl = RawNotificationClientVtbl {
        query_interface: notif_query_interface,
        add_ref: notif_add_ref,
        release: notif_release,
        on_device_state_changed: notif_on_device_state_changed,
        on_device_added: notif_on_device_added,
        on_device_removed: notif_on_device_removed,
        on_default_device_changed: notif_on_default_device_changed,
        on_property_value_changed: notif_on_property_value_changed,
    };

    unsafe extern "system" fn notif_query_interface(
        this: *mut RawNotificationClient,
        riid: *const GUID,
        ppv: *mut *mut core::ffi::c_void,
    ) -> HRESULT {
        if ppv.is_null() || riid.is_null() {
            return HRESULT(-2147467261i32); // E_POINTER
        }
        let iid = *riid;
        if iid == IID_IUNKNOWN || iid == IID_IMMNOTIFICATION_CLIENT {
            *ppv = this as *mut core::ffi::c_void;
            notif_add_ref(this);
            HRESULT(0)
        } else {
            *ppv = ptr::null_mut();
            HRESULT(-2147467262i32) // E_NOINTERFACE
        }
    }

    unsafe extern "system" fn notif_add_ref(this: *mut RawNotificationClient) -> u32 {
        (*this).ref_count.fetch_add(1, Ordering::Relaxed) + 1
    }

    unsafe extern "system" fn notif_release(this: *mut RawNotificationClient) -> u32 {
        let prev = (*this).ref_count.fetch_sub(1, Ordering::Release);
        if prev == 1 {
            std::sync::atomic::fence(Ordering::Acquire);
            drop(Box::from_raw(this));
        }
        prev.saturating_sub(1)
    }

    unsafe extern "system" fn notif_on_device_state_changed(
        this: *mut RawNotificationClient,
        _device_id: PCWSTR,
        _new_state: DEVICE_STATE,
    ) -> HRESULT {
        if let Ok(mut guard) = (*this).shared_state.lock() {
            guard.routing_dirty = true;
        }
        let _ = (*this).app_handle.emit("wasapi-devices-changed", ());
        HRESULT(0)
    }

    unsafe extern "system" fn notif_on_device_added(
        this: *mut RawNotificationClient,
        _device_id: PCWSTR,
    ) -> HRESULT {
        if let Ok(mut guard) = (*this).shared_state.lock() {
            guard.routing_dirty = true;
        }
        let _ = (*this).app_handle.emit("wasapi-devices-changed", ());
        HRESULT(0)
    }

    unsafe extern "system" fn notif_on_device_removed(
        this: *mut RawNotificationClient,
        _device_id: PCWSTR,
    ) -> HRESULT {
        if let Ok(mut guard) = (*this).shared_state.lock() {
            guard.routing_dirty = true;
        }
        let _ = (*this).app_handle.emit("wasapi-devices-changed", ());
        HRESULT(0)
    }

    unsafe extern "system" fn notif_on_default_device_changed(
        this: *mut RawNotificationClient,
        _flow: EDataFlow,
        _role: ERole,
        _default_device_id: PCWSTR,
    ) -> HRESULT {
        let _ = (*this).app_handle.emit("wasapi-devices-changed", ());
        HRESULT(0)
    }

    unsafe extern "system" fn notif_on_property_value_changed(
        _this: *mut RawNotificationClient,
        _device_id: PCWSTR,
        _key: PROPERTYKEY,
    ) -> HRESULT {
        HRESULT(0)
    }

    unsafe fn create_imm_notification_client(
        app_handle: AppHandle,
        shared_state: Arc<Mutex<SharedAudioEngineState>>,
    ) -> IMMNotificationClient {
        let raw = Box::into_raw(Box::new(RawNotificationClient {
            vtbl: &NOTIFICATION_VTBL,
            ref_count: AtomicU32::new(1),
            app_handle,
            shared_state,
        }));
        std::mem::transmute(raw)
    }

    unsafe fn pwstr_to_string(ptr: PWSTR) -> String {
        if ptr.is_null() {
            return String::new();
        }
        let mut len = 0usize;
        while *ptr.0.add(len) != 0 {
            len += 1;
        }
        let slice = slice::from_raw_parts(ptr.0, len);
        String::from_utf16_lossy(slice)
    }

    unsafe fn read_device_property(device: &IMMDevice, key: &PROPERTYKEY) -> Option<String> {
        let store = device.OpenPropertyStore(STGM_READ).ok()?;
        let prop = store.GetValue(key).ok()?;
        let wide_ptr = PropVariantToStringAlloc(&prop).ok()?;
        let value = pwstr_to_string(wide_ptr);
        CoTaskMemFree(Some(wide_ptr.0 as *const _));
        if value.trim().is_empty() {
            None
        } else {
            Some(value.trim().to_string())
        }
    }

    fn is_virtual_dolby_endpoint(name: &str, desc: &str) -> bool {
        let n = name.to_lowercase();
        let d = desc.to_lowercase();
        n.contains("dolby atmos audio")
            || d.contains("dolby atmos audio")
            || n.contains("virtual audio")
            || n.contains("vb-audio")
            || n.contains("cable input")
    }

    fn is_bluetooth_endpoint(name: &str, desc: &str) -> bool {
        let n = name.to_lowercase();
        let d = desc.to_lowercase();
        n.contains("bluetooth")
            || d.contains("bluetooth")
            || n.contains("airpods")
            || n.contains("earbud")
            || n.contains("ptron")
            || n.contains("airlits")
            || n.contains("hands-free")
            || d.contains("bthhfenum")
            || d.contains("btha2dp")
    }

    pub fn enumerate_active_render_endpoints(
        state: &mut SharedAudioEngineState,
    ) -> Vec<WasapiEndpointInfo> {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);

            let enumerator: IMMDeviceEnumerator =
                match CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL) {
                    Ok(e) => e,
                    Err(_) => return Vec::new(),
                };

            let current_default_id = enumerator
                .GetDefaultAudioEndpoint(eRender, eConsole)
                .ok()
                .and_then(|dev| dev.GetId().ok())
                .map(|raw| {
                    let s = pwstr_to_string(raw);
                    CoTaskMemFree(Some(raw.0 as *const _));
                    s
                })
                .unwrap_or_default();

            let collection = match enumerator.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE) {
                Ok(c) => c,
                Err(_) => return Vec::new(),
            };

            let count = collection.GetCount().unwrap_or(0);
            let mut results = Vec::with_capacity(count as usize);

            for i in 0..count {
                if let Ok(device) = collection.Item(i) {
                    let id = match device.GetId() {
                        Ok(raw) => {
                            let s = pwstr_to_string(raw);
                            CoTaskMemFree(Some(raw.0 as *const _));
                            s
                        }
                        Err(_) => continue,
                    };

                    let friendly_name = read_device_property(&device, &PKEY_Device_FriendlyName)
                        .unwrap_or_else(|| "Windows Audio Output".to_string());
                    let desc = read_device_property(&device, &PKEY_Device_DeviceDesc)
                        .unwrap_or_else(|| "Active WASAPI Render Endpoint".to_string());

                    if is_virtual_dolby_endpoint(&friendly_name, &desc) {
                        let _ = set_endpoint_tray_visibility(&id, true);
                        state.dolby_virtual_endpoint_id = Some(id);
                        continue;
                    }

                    let lower = friendly_name.to_lowercase();
                    let is_headphone = lower.contains("headphone")
                        || lower.contains("headset")
                        || lower.contains("earphone")
                        || lower.contains("earbud")
                        || lower.contains("airpods")
                        || lower.contains("bluetooth")
                        || is_bluetooth_endpoint(&friendly_name, &desc);

                    let is_default = id == current_default_id;
                    if is_default && state.original_physical_default_id.is_none() {
                        state.original_physical_default_id = Some(id.clone());
                    }

                    let rel_gain = *state
                        .device_relative_gains
                        .entry(id.clone())
                        .or_insert(1.0_f32);

                    results.push(WasapiEndpointInfo {
                        id,
                        name: friendly_name,
                        description: desc,
                        is_default,
                        is_headphone,
                        is_selected: false,
                        relative_gain: rel_gain,
                    });
                }
            }

            if state.original_physical_default_id.is_none() {
                if let Some(first_phys) = results.first() {
                    state.original_physical_default_id = Some(first_phys.id.clone());
                }
            }

            if state.selected_device_ids.is_empty() {
                if let Some(def) = results.iter().find(|r| r.is_default) {
                    state.selected_device_ids.insert(def.id.clone());
                } else if let Some(first) = results.first() {
                    state.selected_device_ids.insert(first.id.clone());
                }
            }

            for ep in results.iter_mut() {
                ep.is_selected = state.selected_device_ids.contains(&ep.id);
            }

            state.dsp.is_headphone_route = results
                .iter()
                .any(|ep| ep.is_selected && ep.is_headphone);

            results
        }
    }

    struct ActiveWasapiOutputStream {
        device_id: String,
        audio_client: IAudioClient,
        render_client: IAudioRenderClient,
        audio_clock: Option<IAudioClock>,
        event_handle: Option<HANDLE>,
        buffer_frames: u32,
        target_buffer_fill_frames: f64,
        driver_stream_latency_ms: f64,
        wireless_transport_overhead_ms: f64,
        measured_total_latency_ms: f64,
        delay_buffer: HermiteFractionalDelayBuffer,
        temp_delayed: Vec<f32>,
    }

    impl Drop for ActiveWasapiOutputStream {
        fn drop(&mut self) {
            unsafe {
                let _ = self.audio_client.Stop();
                if let Some(h) = self.event_handle.take() {
                    if !h.is_invalid() {
                        let _ = CloseHandle(h);
                    }
                }
            }
        }
    }

    impl ActiveWasapiOutputStream {
        /// Queries real-time hardware presentation + buffer delay `t_i` in milliseconds
        /// using `IAudioClient::GetStreamLatency` + `IAudioClock::GetPosition` + AVDTP wireless transport overhead,
        /// and updates the PI clock-drift controller from the current buffer fill level.
        unsafe fn refresh_realtime_latency_and_drift(&mut self) -> f64 {
            let padding_frames = self.audio_client.GetCurrentPadding().unwrap_or(0) as f64;
            let buffer_padding_ms = (padding_frames * 1000.0) / SAMPLE_RATE;

            // Update PI clock-drift controller so soundcard vs. Bluetooth crystal drift never underruns
            let fill_error = padding_frames - self.target_buffer_fill_frames;
            self.delay_buffer.update_clock_drift_pi(fill_error);

            if let Some(ref clock) = self.audio_clock {
                let mut dev_pos = 0u64;
                let mut qpc_pos = 0u64;
                if let Ok(freq) = clock.GetFrequency() {
                    if freq > 0 {
                        let _ = clock.GetPosition(&mut dev_pos, Some(&mut qpc_pos));
                    }
                }
            }

            self.measured_total_latency_ms = (self.driver_stream_latency_ms
                + self.wireless_transport_overhead_ms
                + buffer_padding_ms)
                .clamp(2.0, 600.0);
            self.measured_total_latency_ms
        }
    }

    fn build_48k_float_stereo_format() -> WAVEFORMATEXTENSIBLE {
        let channels = 2u16;
        let bits_per_sample = 32u16;
        let sample_rate = SAMPLE_RATE as u32;
        let block_align = (channels * bits_per_sample) / 8;
        let avg_bytes_per_sec = sample_rate * block_align as u32;

        WAVEFORMATEXTENSIBLE {
            Format: WAVEFORMATEX {
                wFormatTag: WAVE_FORMAT_EXTENSIBLE as u16,
                nChannels: channels,
                nSamplesPerSec: sample_rate,
                nAvgBytesPerSec: avg_bytes_per_sec,
                nBlockAlign: block_align,
                wBitsPerSample: bits_per_sample,
                cbSize: 22,
            },
            Samples: windows::Win32::Media::Audio::WAVEFORMATEXTENSIBLE_0 {
                wValidBitsPerSample: 32,
            },
            dwChannelMask: 0x3, // SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT
            SubFormat: KSDATAFORMAT_SUBTYPE_IEEE_FLOAT,
        }
    }

    unsafe fn open_loopback_capture_client(
        enumerator: &IMMDeviceEnumerator,
        preferred_endpoint_id: Option<&str>,
        wfx_ptr: *const WAVEFORMATEX,
    ) -> Option<(
        String,
        IAudioClient,
        IAudioCaptureClient,
        Option<IAudioEndpointVolume>,
        Option<HANDLE>,
    )> {
        let mut target_device: Option<IMMDevice> = None;
        let mut target_id = String::new();

        if let Some(pref_id) = preferred_endpoint_id {
            if let Ok(collection) = enumerator.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE) {
                let count = collection.GetCount().unwrap_or(0);
                for i in 0..count {
                    if let Ok(dev) = collection.Item(i) {
                        if let Ok(raw) = dev.GetId() {
                            let id = pwstr_to_string(raw);
                            CoTaskMemFree(Some(raw.0 as *const _));
                            if id == pref_id {
                                target_id = id;
                                target_device = Some(dev);
                                break;
                            }
                        }
                    }
                }
            }
        }

        if target_device.is_none() {
            let def = enumerator.GetDefaultAudioEndpoint(eRender, eConsole).ok()?;
            if let Ok(raw) = def.GetId() {
                target_id = pwstr_to_string(raw);
                CoTaskMemFree(Some(raw.0 as *const _));
            }
            target_device = Some(def);
        }

        let dev = target_device?;
        let endpoint_volume = dev.Activate::<IAudioEndpointVolume>(CLSCTX_ALL, None).ok();
        let client: IAudioClient = dev.Activate(CLSCTX_ALL, None).ok()?;

        let event_handle = CreateEventW(None, false, false, None).ok();
        let mut used_event = false;

        if let Some(h) = event_handle {
            let event_loopback_flags = AUDCLNT_STREAMFLAGS_LOOPBACK
                | AUDCLNT_STREAMFLAGS_EVENTCALLBACK
                | AUDCLNT_STREAMFLAGS_NOPERSIST
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
            if client
                .Initialize(
                    AUDCLNT_SHAREMODE_SHARED,
                    event_loopback_flags,
                    200_000,
                    0,
                    wfx_ptr,
                    None,
                )
                .is_ok()
                && client.SetEventHandle(h).is_ok()
            {
                used_event = true;
            }
        }

        if !used_event {
            let fallback_flags = AUDCLNT_STREAMFLAGS_LOOPBACK
                | AUDCLNT_STREAMFLAGS_NOPERSIST
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
            client
                .Initialize(
                    AUDCLNT_SHAREMODE_SHARED,
                    fallback_flags,
                    200_000,
                    0,
                    wfx_ptr,
                    None,
                )
                .ok()?;
        }

        let capture: IAudioCaptureClient = client.GetService().ok()?;
        let _ = client.Start();
        Some((
            target_id,
            client,
            capture,
            endpoint_volume,
            if used_event { event_handle } else { None },
        ))
    }

    pub fn spawn_wasapi_multi_device_engine(
        app_handle: AppHandle,
        shared_state: Arc<Mutex<SharedAudioEngineState>>,
    ) {
        thread::spawn(move || unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);

            // Elevate audio engine thread to MMCSS "Pro Audio" real-time priority
            let mut mmcss_task_index = 0u32;
            let _mmcss_handle =
                AvSetMmThreadCharacteristicsW(w!("Pro Audio"), &mut mmcss_task_index);

            try_register_bundled_virtual_driver();

            let enumerator: IMMDeviceEnumerator =
                match CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL) {
                    Ok(e) => e,
                    Err(_) => return,
                };

            let notif_client =
                create_imm_notification_client(app_handle.clone(), shared_state.clone());
            let _ = enumerator.RegisterEndpointNotificationCallback(&notif_client);

            if let Ok(mut guard) = shared_state.lock() {
                let _ = enumerate_active_render_endpoints(&mut guard);
            }

            let desired_wfx = build_48k_float_stereo_format();
            let wfx_ptr = &desired_wfx.Format as *const WAVEFORMATEX;

            let mut loopback_bundle: Option<(
                String,
                IAudioClient,
                IAudioCaptureClient,
                Option<IAudioEndpointVolume>,
                Option<HANDLE>,
            )> = None;
            let mut active_outputs: Vec<ActiveWasapiOutputStream> = Vec::new();
            let mut dsp_work_buf: Vec<f32> = Vec::with_capacity(4096);
            let mut last_delay_sync_instant = Instant::now();
            let mut master_volume_scalar = 1.0_f64;

            loop {
                // 1. Handle One-Tap Master Switch ("Use Audio effects") Default Endpoint Switching
                let (master_dirty, routing_dirty, master_on, virtual_id, orig_phys_id) = {
                    if let Ok(mut guard) = shared_state.lock() {
                        let m_dirty = guard.master_switch_dirty;
                        let r_dirty = guard.routing_dirty;
                        guard.master_switch_dirty = false;
                        guard.routing_dirty = false;
                        (
                            m_dirty,
                            r_dirty,
                            guard.dsp.master_enabled,
                            guard.dolby_virtual_endpoint_id.clone(),
                            guard.original_physical_default_id.clone(),
                        )
                    } else {
                        (false, false, true, None, None)
                    }
                };

                if master_dirty {
                    if master_on {
                        if let Some(ref v_id) = virtual_id {
                            let _ = set_default_audio_endpoint(v_id);
                        } else if let Ok(guard) = shared_state.lock() {
                            if let Some(first_sel) = guard.selected_device_ids.iter().next() {
                                let _ = set_default_audio_endpoint(first_sel);
                            }
                        }
                    } else if let Some(ref phys_id) = orig_phys_id {
                        let _ = set_default_audio_endpoint(phys_id);
                    }
                }

                // 2. Rebuild Loopback Capture & Multi-Hardware Render Streams when routing changes
                if routing_dirty || master_dirty || loopback_bundle.is_none() {
                    if let Some((_, ref old_loop_client, _, _, old_evt)) = loopback_bundle.take() {
                        let _ = old_loop_client.Stop();
                        if let Some(h) = old_evt {
                            if !h.is_invalid() {
                                let _ = CloseHandle(h);
                            }
                        }
                    }
                    active_outputs.clear();

                    loopback_bundle = open_loopback_capture_client(
                        &enumerator,
                        virtual_id.as_deref(),
                        wfx_ptr,
                    );

                    let capture_source_id = loopback_bundle
                        .as_ref()
                        .map(|(id, _, _, _, _)| id.clone())
                        .unwrap_or_default();

                    let (target_ids, rel_gains) = {
                        shared_state
                            .lock()
                            .map(|g| {
                                (
                                    g.selected_device_ids.clone(),
                                    g.device_relative_gains.clone(),
                                )
                            })
                            .unwrap_or_default()
                    };

                    if let Ok(collection) =
                        enumerator.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)
                    {
                        let count = collection.GetCount().unwrap_or(0);
                        for i in 0..count {
                            if let Ok(dev) = collection.Item(i) {
                                let dev_id = match dev.GetId() {
                                    Ok(raw) => {
                                        let s = pwstr_to_string(raw);
                                        CoTaskMemFree(Some(raw.0 as *const _));
                                        s
                                    }
                                    Err(_) => continue,
                                };

                                if !target_ids.contains(&dev_id) || dev_id == capture_source_id {
                                    continue;
                                }

                                let friendly_name =
                                    read_device_property(&dev, &PKEY_Device_FriendlyName)
                                        .unwrap_or_default();
                                let desc = read_device_property(&dev, &PKEY_Device_DeviceDesc)
                                    .unwrap_or_default();
                                let is_bt = is_bluetooth_endpoint(&friendly_name, &desc);

                                if let Ok(render_audio_client) =
                                    dev.Activate::<IAudioClient>(CLSCTX_ALL, None)
                                {
                                    let evt = CreateEventW(None, false, false, None).ok();
                                    let mut used_evt = false;
                                    if let Some(h) = evt {
                                        let event_render_flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK
                                            | AUDCLNT_STREAMFLAGS_NOPERSIST
                                            | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                                            | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
                                        if render_audio_client
                                            .Initialize(
                                                AUDCLNT_SHAREMODE_SHARED,
                                                event_render_flags,
                                                400_000, // 40ms shared buffer for glitch-free multi-sink output
                                                0,
                                                wfx_ptr,
                                                None,
                                            )
                                            .is_ok()
                                            && render_audio_client.SetEventHandle(h).is_ok()
                                        {
                                            used_evt = true;
                                        }
                                    }

                                    if !used_evt {
                                        let fallback_flags = AUDCLNT_STREAMFLAGS_NOPERSIST
                                            | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                                            | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
                                        if render_audio_client
                                            .Initialize(
                                                AUDCLNT_SHAREMODE_SHARED,
                                                fallback_flags,
                                                400_000,
                                                0,
                                                wfx_ptr,
                                                None,
                                            )
                                            .is_err()
                                        {
                                            continue;
                                        }
                                    }

                                    if let Ok(render_client) =
                                        render_audio_client.GetService::<IAudioRenderClient>()
                                    {
                                        let audio_clock =
                                            render_audio_client.GetService::<IAudioClock>().ok();
                                        let buf_frames =
                                            render_audio_client.GetBufferSize().unwrap_or(1920);
                                        let stream_lat_100ns = render_audio_client
                                            .GetStreamLatency()
                                            .unwrap_or(150_000);
                                        let driver_lat_ms =
                                            (stream_lat_100ns as f64) / 10_000.0;
                                        // Account for Bluetooth AVDTP packetization + codec buffer overhead (~110 ms)
                                        // vs wired/motherboard speaker pipeline (~15 ms)
                                        let wireless_overhead_ms =
                                            if is_bt { 110.0 } else { 15.0 };

                                        let _ = render_audio_client.Start();
                                        let mut delay_buf =
                                            HermiteFractionalDelayBuffer::new(262_144);
                                        let rel_g =
                                            *rel_gains.get(&dev_id).unwrap_or(&1.0_f32) as f64;
                                        delay_buf.set_effective_gain(master_volume_scalar * rel_g);

                                        let mut stream = ActiveWasapiOutputStream {
                                            device_id: dev_id,
                                            audio_client: render_audio_client,
                                            render_client,
                                            audio_clock,
                                            event_handle: if used_evt { evt } else { None },
                                            buffer_frames: buf_frames,
                                            target_buffer_fill_frames: (buf_frames as f64) * 0.45,
                                            driver_stream_latency_ms: driver_lat_ms,
                                            wireless_transport_overhead_ms: wireless_overhead_ms,
                                            measured_total_latency_ms: driver_lat_ms
                                                + wireless_overhead_ms,
                                            delay_buffer: delay_buf,
                                            temp_delayed: vec![0.0; 4096],
                                        };
                                        let _ = stream.refresh_realtime_latency_and_drift();
                                        active_outputs.push(stream);
                                    }
                                }
                            }
                        }
                    }

                    // Initial Delay-Matching across all active hardware outputs:
                    // t_max = max(t_1, ..., t_N), delta_t_i = t_max - t_i
                    if active_outputs.len() >= 2 {
                        let t_max = active_outputs
                            .iter()
                            .map(|o| o.measured_total_latency_ms)
                            .fold(0.0_f64, f64::max);
                        for out in active_outputs.iter_mut() {
                            let delta_ti = (t_max - out.measured_total_latency_ms).max(0.0);
                            out.delay_buffer.set_delay_ms(delta_ti);
                        }
                    } else if let Some(single) = active_outputs.first_mut() {
                        single.delay_buffer.set_delay_ms(0.0);
                    }
                }

                // 3. Periodically query Windows Master Volume & Mute state + refresh PI Clock-Drift
                //    & Delay Compensation every 150ms
                if last_delay_sync_instant.elapsed() >= Duration::from_millis(150) {
                    last_delay_sync_instant = Instant::now();

                    if let Some((_, _, _, Some(ref ep_vol), _)) = loopback_bundle {
                        let is_muted = ep_vol
                            .GetMute()
                            .map(|b| b.as_bool())
                            .unwrap_or(false);
                        let master_scalar = ep_vol
                            .GetMasterVolumeLevelScalar()
                            .unwrap_or(1.0)
                            .clamp(0.0, 1.0) as f64;
                        master_volume_scalar = if is_muted { 0.0 } else { master_scalar };
                    }

                    let rel_gains = shared_state
                        .lock()
                        .map(|g| g.device_relative_gains.clone())
                        .unwrap_or_default();

                    for out in active_outputs.iter_mut() {
                        let rel_g = *rel_gains.get(&out.device_id).unwrap_or(&1.0_f32) as f64;
                        out.delay_buffer
                            .set_effective_gain(master_volume_scalar * rel_g);
                    }

                    if active_outputs.len() >= 2 {
                        let mut t_max = 0.0_f64;
                        for out in active_outputs.iter_mut() {
                            let ti = out.refresh_realtime_latency_and_drift();
                            if ti > t_max {
                                t_max = ti;
                            }
                        }
                        for out in active_outputs.iter_mut() {
                            let delta_ti = (t_max - out.measured_total_latency_ms).max(0.0);
                            out.delay_buffer.set_delay_ms(delta_ti);
                        }
                    } else if let Some(single) = active_outputs.first_mut() {
                        let _ = single.refresh_realtime_latency_and_drift();
                    }
                }

                let (_, _, ref capture_client, _, capture_evt) = match loopback_bundle {
                    Some(ref b) => b,
                    None => {
                        thread::sleep(Duration::from_millis(10));
                        continue;
                    }
                };

                // 4. Event-Driven Wait or Low-Latency Poll for Captured System Audio Packets
                let mut packet_frames = match capture_client.GetNextPacketSize() {
                    Ok(sz) => sz,
                    Err(_) => {
                        loopback_bundle = None;
                        thread::sleep(Duration::from_millis(5));
                        continue;
                    }
                };

                if packet_frames == 0 {
                    if let Some(h) = *capture_evt {
                        let _ = WaitForSingleObject(h, 4);
                    } else {
                        thread::sleep(Duration::from_millis(2));
                    }
                    continue;
                }

                while packet_frames > 0 {
                    let mut data_ptr: *mut u8 = ptr::null_mut();
                    let mut num_frames_read = 0u32;
                    let mut flags = 0u32;

                    if capture_client
                        .GetBuffer(
                            &mut data_ptr,
                            &mut num_frames_read,
                            &mut flags,
                            None,
                            None,
                        )
                        .is_ok()
                    {
                        let sample_count = (num_frames_read as usize) * 2;
                        dsp_work_buf.resize(sample_count, 0.0);

                        if (flags & 0x2) != 0 || data_ptr.is_null() {
                            dsp_work_buf.fill(0.0);
                        } else {
                            let float_slice =
                                slice::from_raw_parts(data_ptr as *const f32, sample_count);
                            dsp_work_buf.copy_from_slice(float_slice);
                        }

                        let _ = capture_client.ReleaseBuffer(num_frames_read);

                        // Execute 7-Stage DAX3 Psychoacoustic DSP Pipeline when Master Switch is ON
                        if let Ok(mut guard) = shared_state.lock() {
                            for out in active_outputs.iter_mut() {
                                let rel_g = *guard
                                    .device_relative_gains
                                    .get(&out.device_id)
                                    .unwrap_or(&1.0_f32) as f64;
                                out.delay_buffer
                                    .set_effective_gain(master_volume_scalar * rel_g);
                            }
                            if guard.dsp.master_enabled {
                                guard.dsp.process_interleaved_buffer(&mut dsp_work_buf);
                            }
                        }

                        // Write simultaneously to all ticked hardware endpoints with Hermite delay sync
                        for out in active_outputs.iter_mut() {
                            if out.temp_delayed.len() < sample_count {
                                out.temp_delayed.resize(sample_count, 0.0);
                            }
                            out.delay_buffer.process_interleaved(
                                &dsp_work_buf[..sample_count],
                                &mut out.temp_delayed[..sample_count],
                            );

                            let padding = out.audio_client.GetCurrentPadding().unwrap_or(0);
                            let avail_frames = out.buffer_frames.saturating_sub(padding);
                            let frames_to_write = num_frames_read.min(avail_frames);

                            if frames_to_write > 0 {
                                if let Ok(dst_ptr) = out.render_client.GetBuffer(frames_to_write) {
                                    let dst_slice = slice::from_raw_parts_mut(
                                        dst_ptr as *mut f32,
                                        (frames_to_write as usize) * 2,
                                    );
                                    dst_slice.copy_from_slice(
                                        &out.temp_delayed[..(frames_to_write as usize) * 2],
                                    );
                                    let _ = out.render_client.ReleaseBuffer(frames_to_write, 0);
                                }
                            }
                        }
                    } else {
                        break;
                    }

                    packet_frames = capture_client.GetNextPacketSize().unwrap_or(0);
                }
            }
        });
    }
}

pub fn enumerate_devices_cross_platform(
    shared_state: &Arc<Mutex<SharedAudioEngineState>>,
) -> Vec<WasapiEndpointInfo> {
    #[cfg(windows)]
    {
        if let Ok(mut guard) = shared_state.lock() {
            return win_wasapi::enumerate_active_render_endpoints(&mut guard);
        }
    }

    let mut guard = match shared_state.lock() {
        Ok(g) => g,
        Err(_) => return Vec::new(),
    };
    if guard.selected_device_ids.is_empty() {
        guard
            .selected_device_ids
            .insert("default_wasapi_render".to_string());
    }
    let rel_gain = *guard
        .device_relative_gains
        .entry("default_wasapi_render".to_string())
        .or_insert(1.0_f32);
    vec![WasapiEndpointInfo {
        id: "default_wasapi_render".to_string(),
        name: "Loudspeakers".to_string(),
        description: "System default stereo output".to_string(),
        is_default: true,
        is_headphone: false,
        is_selected: guard.selected_device_ids.contains("default_wasapi_render"),
        relative_gain: rel_gain,
    }]
}

pub fn start_audio_engine(
    app_handle: tauri::AppHandle,
    shared_state: Arc<Mutex<SharedAudioEngineState>>,
) {
    #[cfg(windows)]
    {
        win_wasapi::spawn_wasapi_multi_device_engine(app_handle, shared_state);
    }
    #[cfg(not(windows))]
    {
        let _ = (app_handle, shared_state);
    }
}
