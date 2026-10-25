// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod dsp;
mod wasapi_engine;

use std::sync::{Arc, Mutex};
use tauri::State;
use wasapi_engine::{
    enumerate_devices_cross_platform, start_audio_engine, SharedAudioEngineState,
    WasapiEndpointInfo,
};

#[tauri::command]
fn get_wasapi_devices(
    state: State<'_, Arc<Mutex<SharedAudioEngineState>>>,
) -> Vec<WasapiEndpointInfo> {
    enumerate_devices_cross_platform(state.inner())
}

#[tauri::command]
fn set_selected_wasapi_devices(
    device_ids: Vec<String>,
    state: State<'_, Arc<Mutex<SharedAudioEngineState>>>,
) -> Result<(), String> {
    if let Ok(mut guard) = state.lock() {
        guard.selected_device_ids = device_ids.into_iter().collect();
        guard.routing_dirty = true;
    }
    Ok(())
}

#[tauri::command]
fn update_dsp_config(
    master_enabled: bool,
    mode: String,
    bands_db: Vec<f32>,
    surround_virtualiser: bool,
    volume_leveller: bool,
    state: State<'_, Arc<Mutex<SharedAudioEngineState>>>,
) -> Result<(), String> {
    if let Ok(mut guard) = state.lock() {
        guard.dsp.master_enabled = master_enabled;
        guard.dsp.surround_enabled = surround_virtualiser || mode == "SPATIAL_AUDIO";
        guard.dsp.volume_leveller_enabled = volume_leveller;
        guard.dsp.update_eq_bands(&bands_db);
    }
    Ok(())
}

fn main() {
    let shared_state = Arc::new(Mutex::new(SharedAudioEngineState::new()));
    let state_for_setup = shared_state.clone();

    tauri::Builder::default()
        .manage(shared_state)
        .setup(move |app| {
            start_audio_engine(app.handle().clone(), state_for_setup.clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_wasapi_devices,
            set_selected_wasapi_devices,
            update_dsp_config
        ])
        .run(tauri::generate_context!())
        .expect("error while running Dolby Atmos PC application");
}
