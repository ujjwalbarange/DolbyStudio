#[cfg(windows)]
pub mod win_policy {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use std::process::Command;
    use std::ptr;
    use windows::core::{Interface, GUID, HRESULT, PCWSTR};
    use windows::Win32::Devices::FunctionDiscovery::PKEY_Device_FriendlyName;
    use windows::Win32::Media::Audio::{
        eCommunications, eConsole, eMultimedia, ERole, WAVEFORMATEX,
    };
    use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CLSCTX_ALL, COINIT_MULTITHREADED,
    };
    use windows::Win32::UI::Shell::PropertiesSystem::PROPERTYKEY;

    /// CLSID_CPolicyConfigClient: {870af99c-171d-4f9e-af0d-e63df40c2bc9}
    pub const CLSID_POLICY_CONFIG_CLIENT: GUID =
        GUID::from_u128(0x870af99c_171d_4f9e_af0d_e63df40c2bc9);

    /// IID_IPolicyConfig (Windows 10 / 11): {f8679f50-850a-41cf-9c72-430f290290c8}
    pub const IID_IPOLICY_CONFIG: GUID =
        GUID::from_u128(0xf8679f50_850a_41cf_9c72_430f290290c8);

    /// IID_IPolicyConfigVista (Fallback): {568b9108-44bf-40b4-9006-86afe5b5a620}
    pub const IID_IPOLICY_CONFIG_VISTA: GUID =
        GUID::from_u128(0x568b9108_44bf_40b4_9006_86afe5b5a620);

    /// Raw COM VTable for Windows Core Audio undocumented `IPolicyConfig` interface.
    #[repr(C)]
    pub struct IPolicyConfigVtbl {
        // IUnknown
        pub query_interface: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            riid: *const GUID,
            ppv: *mut *mut core::ffi::c_void,
        ) -> HRESULT,
        pub add_ref: unsafe extern "system" fn(this: *mut IPolicyConfigRaw) -> u32,
        pub release: unsafe extern "system" fn(this: *mut IPolicyConfigRaw) -> u32,

        // IPolicyConfig methods
        pub get_mix_format: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            format: *mut *mut WAVEFORMATEX,
        ) -> HRESULT,
        pub get_device_format: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            default_fmt: i32,
            format: *mut *mut WAVEFORMATEX,
        ) -> HRESULT,
        pub reset_device_format: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
        ) -> HRESULT,
        pub set_device_format: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            endpoint_format: *const WAVEFORMATEX,
            mix_format: *const WAVEFORMATEX,
        ) -> HRESULT,
        pub get_processing_period: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            default_period: i32,
            pm_default_period: *mut i64,
            pm_min_period: *mut i64,
        ) -> HRESULT,
        pub set_processing_period: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            period: *const i64,
        ) -> HRESULT,
        pub get_share_mode: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            mode: *mut core::ffi::c_void,
        ) -> HRESULT,
        pub set_share_mode: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            mode: *const core::ffi::c_void,
        ) -> HRESULT,
        pub get_property_value: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            fx_store: i32,
            key: *const PROPERTYKEY,
            value: *mut PROPVARIANT,
        ) -> HRESULT,
        pub set_property_value: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            fx_store: i32,
            key: *const PROPERTYKEY,
            value: *const PROPVARIANT,
        ) -> HRESULT,
        pub set_default_endpoint: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            role: ERole,
        ) -> HRESULT,
        pub set_endpoint_visibility: unsafe extern "system" fn(
            this: *mut IPolicyConfigRaw,
            device_name: PCWSTR,
            visible: i32,
        ) -> HRESULT,
    }

    #[repr(C)]
    pub struct IPolicyConfigRaw {
        pub lp_vtbl: *const IPolicyConfigVtbl,
    }

    fn to_wide_null(s: &str) -> Vec<u16> {
        OsStr::new(s).encode_wide().chain(std::iter::once(0)).collect()
    }

    unsafe fn acquire_policy_config() -> Option<*mut IPolicyConfigRaw> {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        let unknown: windows::core::IUnknown =
            CoCreateInstance(&CLSID_POLICY_CONFIG_CLIENT, None, CLSCTX_ALL).ok()?;

        let mut raw_ptr: *mut core::ffi::c_void = ptr::null_mut();
        if (unknown.vtable().QueryInterface)(
            unknown.as_raw(),
            &IID_IPOLICY_CONFIG,
            &mut raw_ptr,
        )
        .is_ok()
            && !raw_ptr.is_null()
        {
            return Some(raw_ptr as *mut IPolicyConfigRaw);
        }

        if (unknown.vtable().QueryInterface)(
            unknown.as_raw(),
            &IID_IPOLICY_CONFIG_VISTA,
            &mut raw_ptr,
        )
        .is_ok()
            && !raw_ptr.is_null()
        {
            return Some(raw_ptr as *mut IPolicyConfigRaw);
        }

        None
    }

    /// Sets the specified endpoint ID as the Windows default playback device across
    /// Console, Multimedia, and Communications roles via `IPolicyConfig::SetDefaultEndpoint`.
    pub fn set_default_audio_endpoint(device_id: &str) -> bool {
        if device_id.is_empty() {
            return false;
        }
        unsafe {
            let policy = match acquire_policy_config() {
                Some(p) => p,
                None => return false,
            };
            let wide_id = to_wide_null(device_id);
            let pcwstr = PCWSTR(wide_id.as_ptr());

            let hr1 = ((*(*policy).lp_vtbl).set_default_endpoint)(policy, pcwstr, eConsole);
            let hr2 = ((*(*policy).lp_vtbl).set_default_endpoint)(policy, pcwstr, eMultimedia);
            let _ = ((*(*policy).lp_vtbl).set_default_endpoint)(policy, pcwstr, eCommunications);

            ((*(*policy).lp_vtbl).release)(policy);
            hr1.is_ok() || hr2.is_ok()
        }
    }

    /// Ensures the endpoint is visible in the Windows Sound Output tray.
    pub fn set_endpoint_tray_visibility(device_id: &str, visible: bool) -> bool {
        if device_id.is_empty() {
            return false;
        }
        unsafe {
            let policy = match acquire_policy_config() {
                Some(p) => p,
                None => return false,
            };
            let wide_id = to_wide_null(device_id);
            let pcwstr = PCWSTR(wide_id.as_ptr());
            let hr = ((*(*policy).lp_vtbl).set_endpoint_visibility)(
                policy,
                pcwstr,
                if visible { 1 } else { 0 },
            );
            ((*(*policy).lp_vtbl).release)(policy);
            hr.is_ok()
        }
    }

    /// Attempts to provision or enable the bundled "Dolby Atmos Audio" virtual driver
    /// via Windows `pnputil` if running with sufficient privileges.
    pub fn try_register_bundled_virtual_driver() {
        let candidate_paths = [
            "driver/dolby_atmos_virtual_audio.inf",
            "resources/driver/dolby_atmos_virtual_audio.inf",
        ];
        for path in candidate_paths {
            if std::path::Path::new(path).exists() {
                let _ = Command::new("pnputil")
                    .args(["/add-driver", path, "/install"])
                    .output();
                break;
            }
        }
    }

    /// Suppresses unused warning for PKEY_Device_FriendlyName import used by endpoint labeler.
    #[allow(dead_code)]
    pub fn friendly_name_key() -> PROPERTYKEY {
        PKEY_Device_FriendlyName
    }
}
