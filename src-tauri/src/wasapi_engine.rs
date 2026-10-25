use crate::dsp::{HermiteFractionalDelayBuffer, PsychoacousticDspPipeline, SAMPLE_RATE};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
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
}

pub struct SharedAudioEngineState {
    pub dsp: PsychoacousticDspPipeline,
    pub selected_device_ids: HashSet<String>,
    pub routing_dirty: bool,
}

impl SharedAudioEngineState {
    pub fn new() -> Self {
        Self {
            dsp: PsychoacousticDspPipeline::new(),
            selected_device_ids: HashSet::new(),
            routing_dirty: true,
        }
    }
}

#[cfg(windows)]
mod win_wasapi {
    use super::*;
    use std::ptr;
    use std::slice;
    use std::thread;
    use std::time::Duration;
    use tauri::{AppHandle, Emitter};
    use windows::core::{implement, Interface, GUID, HRESULT, PCWSTR, PWSTR};
    use windows::Win32::Devices::FunctionDiscovery::{
        PKEY_Device_DeviceDesc, PKEY_Device_FriendlyName,
    };
    use windows::Win32::Foundation::PROPERTYKEY;
    use windows::Win32::Media::Audio::{
        eConsole, eRender, IAudioCaptureClient, IAudioClient, IAudioRenderClient, IMMDevice,
        IMMDeviceEnumerator, IMMNotificationClient, IMMNotificationClient_Impl, MMDeviceEnumerator,
        AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
        AUDCLNT_STREAMFLAGS_LOOPBACK, AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, DEVICE_STATE,
        DEVICE_STATE_ACTIVE, WAVEFORMATEX, WAVEFORMATEXTENSIBLE,
    };
    use windows::Win32::Media::KernelStreaming::WAVE_FORMAT_EXTENSIBLE;
    use windows::Win32::Media::Multimedia::KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
    use windows::Win32::System::Com::StructuredStorage::PropVariantToStringAlloc;
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoTaskMemFree, CLSCTX_ALL, COINIT_MULTITHREADED,
        STGM_READ,
    };

    #[implement(IMMNotificationClient)]
    struct HotplugNotificationClient {
        app_handle: AppHandle,
    }

    impl IMMNotificationClient_Impl for HotplugNotificationClient_Impl {
        fn OnDeviceStateChanged(&self, _pwstrdeviceid: &PCWSTR, _dwnewstate: DEVICE_STATE) -> windows::core::Result<()> {
            let _ = self.app_handle.emit("wasapi-devices-changed", ());
            Ok(())
        }

        fn OnDeviceAdded(&self, _pwstrdeviceid: &PCWSTR) -> windows::core::Result<()> {
            let _ = self.app_handle.emit("wasapi-devices-changed", ());
            Ok(())
        }

        fn OnDeviceRemoved(&self, _pwstrdeviceid: &PCWSTR) -> windows::core::Result<()> {
            let _ = self.app_handle.emit("wasapi-devices-changed", ());
            Ok(())
        }

        fn OnDefaultDeviceChanged(
            &self,
            _flow: windows::Win32::Media::Audio::EDataFlow,
            _role: windows::Win32::Media::Audio::ERole,
            _pwstrdefaultdeviceid: &PCWSTR,
        ) -> windows::core::Result<()> {
            let _ = self.app_handle.emit("wasapi-devices-changed", ());
            Ok(())
        }

        fn OnPropertyValueChanged(&self, _pwstrdeviceid: &PCWSTR, _key: &PROPERTYKEY) -> windows::core::Result<()> {
            Ok(())
        }
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

    pub fn enumerate_active_render_endpoints(
        selected_ids: &mut HashSet<String>,
    ) -> Vec<WasapiEndpointInfo> {
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);

            let enumerator: IMMDeviceEnumerator =
                match CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL) {
                    Ok(e) => e,
                    Err(_) => return Vec::new(),
                };

            let default_id = enumerator
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

                    let lower = friendly_name.to_lowercase();
                    let is_headphone = lower.contains("headphone")
                        || lower.contains("headset")
                        || lower.contains("earphone")
                        || lower.contains("earbud")
                        || lower.contains("airpods")
                        || lower.contains("bluetooth");

                    let is_default = id == default_id;

                    results.push(WasapiEndpointInfo {
                        id,
                        name: friendly_name,
                        description: desc,
                        is_default,
                        is_headphone,
                        is_selected: false,
                    });
                }
            }

            if selected_ids.is_empty() {
                if let Some(def) = results.iter().find(|r| r.is_default) {
                    selected_ids.insert(def.id.clone());
                } else if let Some(first) = results.first() {
                    selected_ids.insert(first.id.clone());
                }
            }

            for ep in results.iter_mut() {
                ep.is_selected = selected_ids.contains(&ep.id);
            }

            results
        }
    }

    struct ActiveWasapiOutputStream {
        device_id: String,
        audio_client: IAudioClient,
        render_client: IAudioRenderClient,
        buffer_frames: u32,
        hardware_latency_ms: f64,
        delay_buffer: HermiteFractionalDelayBuffer,
        temp_delayed: Vec<f32>,
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

    pub fn spawn_wasapi_multi_device_engine(
        app_handle: AppHandle,
        shared_state: Arc<Mutex<SharedAudioEngineState>>,
    ) {
        thread::spawn(move || unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);

            let enumerator: IMMDeviceEnumerator =
                match CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL) {
                    Ok(e) => e,
                    Err(_) => return,
                };

            let notif_client: IMMNotificationClient = HotplugNotificationClient {
                app_handle: app_handle.clone(),
            }
            .into();
            let _ = enumerator.RegisterEndpointNotificationCallback(&notif_client);

            let desired_wfx = build_48k_float_stereo_format();
            let wfx_ptr = &desired_wfx.Format as *const WAVEFORMATEX;

            // Open WASAPI Loopback Capture on Default Render Endpoint
            let default_render = match enumerator.GetDefaultAudioEndpoint(eRender, eConsole) {
                Ok(d) => d,
                Err(_) => return,
            };

            let loopback_client: IAudioClient = match default_render.Activate(CLSCTX_ALL, None) {
                Ok(c) => c,
                Err(_) => return,
            };

            let loopback_flags = AUDCLNT_STREAMFLAGS_LOOPBACK
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;

            // 20 ms buffer duration (in 100-ns units = 200,000)
            if loopback_client
                .Initialize(
                    AUDCLNT_SHAREMODE_SHARED,
                    loopback_flags,
                    200_000,
                    0,
                    wfx_ptr,
                    None,
                )
                .is_err()
            {
                return;
            }

            let capture_client: IAudioCaptureClient = match loopback_client.GetService() {
                Ok(s) => s,
                Err(_) => return,
            };

            let _ = loopback_client.Start();

            let mut active_outputs: Vec<ActiveWasapiOutputStream> = Vec::new();
            let mut dsp_work_buf: Vec<f32> = Vec::with_capacity(4096);

            loop {
                // Check if user changed selected output devices
                let should_rebuild = {
                    if let Ok(mut guard) = shared_state.lock() {
                        if guard.routing_dirty {
                            guard.routing_dirty = false;
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                };

                if should_rebuild {
                    for out in active_outputs.drain(..) {
                        let _ = out.audio_client.Stop();
                    }

                    let target_ids = {
                        shared_state
                            .lock()
                            .map(|g| g.selected_device_ids.clone())
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

                                if !target_ids.contains(&dev_id) {
                                    continue;
                                }

                                if let Ok(render_audio_client) =
                                    dev.Activate::<IAudioClient>(CLSCTX_ALL, None)
                                {
                                    let render_flags = AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                                        | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
                                    if render_audio_client
                                        .Initialize(
                                            AUDCLNT_SHAREMODE_SHARED,
                                            render_flags,
                                            300_000,
                                            0,
                                            wfx_ptr,
                                            None,
                                        )
                                        .is_ok()
                                    {
                                        if let Ok(render_client) =
                                            render_audio_client.GetService::<IAudioRenderClient>()
                                        {
                                            let buf_frames =
                                                render_audio_client.GetBufferSize().unwrap_or(1440);
                                            // Query device stream latency (100-ns units -> ms)
                                            let stream_lat_100ns = render_audio_client
                                                .GetStreamLatency()
                                                .unwrap_or(150_000);
                                            let hw_lat_ms = (stream_lat_100ns as f64) / 10_000.0
                                                + ((buf_frames as f64) * 1000.0 / SAMPLE_RATE)
                                                    * 0.5;

                                            let _ = render_audio_client.Start();
                                            active_outputs.push(ActiveWasapiOutputStream {
                                                device_id: dev_id,
                                                audio_client: render_audio_client,
                                                render_client,
                                                buffer_frames: buf_frames,
                                                hardware_latency_ms: hw_lat_ms,
                                                delay_buffer: HermiteFractionalDelayBuffer::new(
                                                    32768,
                                                ),
                                                temp_delayed: vec![0.0; 4096],
                                            });
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Delay-Matching Equation: t_max = max(t_1, ..., t_N), delta_t_i = t_max - t_i
                    if active_outputs.len() >= 2 {
                        let t_max = active_outputs
                            .iter()
                            .map(|o| o.hardware_latency_ms)
                            .fold(0.0_f64, f64::max);
                        for out in active_outputs.iter_mut() {
                            let delta_ti = (t_max - out.hardware_latency_ms).max(0.0);
                            out.delay_buffer.set_delay_ms(delta_ti);
                        }
                    } else if let Some(single) = active_outputs.first_mut() {
                        single.delay_buffer.set_delay_ms(0.0);
                    }
                }

                // Read captured system audio packets from WASAPI Loopback
                let mut packet_frames = match capture_client.GetNextPacketSize() {
                    Ok(sz) => sz,
                    Err(_) => {
                        thread::sleep(Duration::from_millis(2));
                        continue;
                    }
                };

                if packet_frames == 0 {
                    thread::sleep(Duration::from_millis(2));
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
                            // AUDCLNT_BUFFERFLAGS_SILENT
                            dsp_work_buf.fill(0.0);
                        } else {
                            let float_slice =
                                slice::from_raw_parts(data_ptr as *const f32, sample_count);
                            dsp_work_buf.copy_from_slice(float_slice);
                        }

                        let _ = capture_client.ReleaseBuffer(num_frames_read);

                        // Process captured system audio through the 5-Stage Psychoacoustic DSP Pipeline
                        if let Ok(mut guard) = shared_state.lock() {
                            guard.dsp.process_interleaved_buffer(&mut dsp_work_buf);
                        }

                        // Broadcast simultaneously to every selected WASAPI output with Hermite delay compensation
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
            return win_wasapi::enumerate_active_render_endpoints(&mut guard.selected_device_ids);
        }
    }

    // Fallback when running on non-Windows host during development
    let mut guard = match shared_state.lock() {
        Ok(g) => g,
        Err(_) => return Vec::new(),
    };
    if guard.selected_device_ids.is_empty() {
        guard
            .selected_device_ids
            .insert("default_wasapi_render".to_string());
    }
    vec![WasapiEndpointInfo {
        id: "default_wasapi_render".to_string(),
        name: "Loudspeakers".to_string(),
        description: "System default stereo output".to_string(),
        is_default: true,
        is_headphone: false,
        is_selected: guard.selected_device_ids.contains("default_wasapi_render"),
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
