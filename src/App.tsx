import React, { useEffect, useState, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import {
  AUDIO_MODES,
  AudioModeId,
  EqualiserPresetId,
  ISO_10_BAND_LABELS,
  PRESET_CURVES,
  ProfileSettings,
  WasapiEndpoint,
} from "./types/dolby";
import {
  ArrowLeft,
  ArrowRight,
  Pencil,
  Plus,
  RotateCcw,
  Settings,
} from "lucide-react";

type MobileScreen = "HERO" | "SETTINGS" | "EDITOR";

const INITIAL_PROFILES: Record<AudioModeId, ProfileSettings> = {
  SPATIAL_AUDIO: {
    mode: "SPATIAL_AUDIO",
    preset: "BRILLIANT_TREBLE",
    manualBandsDb: [...PRESET_CURVES.BRILLIANT_TREBLE],
    macroBassTiltDb: 0,
    macroTrebleTiltDb: 0,
    surroundVirtualiser: true,
    volumeLeveller: true,
  },
  SMART_AUDIO: {
    mode: "SMART_AUDIO",
    preset: "BRILLIANT_TREBLE",
    manualBandsDb: [...PRESET_CURVES.BRILLIANT_TREBLE],
    macroBassTiltDb: 0,
    macroTrebleTiltDb: 0,
    surroundVirtualiser: true,
    volumeLeveller: true,
  },
  MUSIC: {
    mode: "MUSIC",
    preset: "BRILLIANT_TREBLE",
    manualBandsDb: [...PRESET_CURVES.BRILLIANT_TREBLE],
    macroBassTiltDb: 0,
    macroTrebleTiltDb: 0,
    surroundVirtualiser: true,
    volumeLeveller: true,
  },
  FILM: {
    mode: "FILM",
    preset: "BASS_BOOST",
    manualBandsDb: [...PRESET_CURVES.BASS_BOOST],
    macroBassTiltDb: 0,
    macroTrebleTiltDb: 0,
    surroundVirtualiser: true,
    volumeLeveller: true,
  },
  GAME: {
    mode: "GAME",
    preset: "BASS_BOOST",
    manualBandsDb: [...PRESET_CURVES.BASS_BOOST],
    macroBassTiltDb: 0,
    macroTrebleTiltDb: 0,
    surroundVirtualiser: true,
    volumeLeveller: true,
  },
  PODCAST: {
    mode: "PODCAST",
    preset: "VOCAL_BOOST",
    manualBandsDb: [...PRESET_CURVES.VOCAL_BOOST],
    macroBassTiltDb: 0,
    macroTrebleTiltDb: 0,
    surroundVirtualiser: false,
    volumeLeveller: true,
  },
  CUSTOM: {
    mode: "CUSTOM",
    preset: "MANUAL",
    manualBandsDb: [...PRESET_CURVES.MANUAL],
    macroBassTiltDb: 0,
    macroTrebleTiltDb: 0,
    surroundVirtualiser: true,
    volumeLeveller: true,
  },
};

export default function App() {
  const [useAudioEffects, setUseAudioEffects] = useState<boolean>(true);
  const [selectedMode, setSelectedMode] = useState<AudioModeId>("MUSIC");
  const [editingMode, setEditingMode] = useState<AudioModeId>("MUSIC");
  const [profiles, setProfiles] =
    useState<Record<AudioModeId, ProfileSettings>>(INITIAL_PROFILES);
  const [endpoints, setEndpoints] = useState<WasapiEndpoint[]>([]);
  const [mobileScreen, setMobileScreen] = useState<MobileScreen>("HERO");
  const [isWideDesktop, setIsWideDesktop] = useState<boolean>(
    typeof window !== "undefined" ? window.innerWidth >= 768 : true
  );

  // Track responsive breakpoint (< 768px compact single-column vs >= 768px dual-column PC dashboard)
  useEffect(() => {
    const onResize = () => {
      setIsWideDesktop(window.innerWidth >= 768);
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  // Query real active Windows WASAPI playback endpoints (or browser WebAudio outputs fallback)
  const fetchRealHardwareDevices = useCallback(async () => {
    try {
      const devices = await invoke<WasapiEndpoint[]>("get_wasapi_devices");
      if (devices && devices.length > 0) {
        setEndpoints(devices);
        return;
      }
    } catch {
      // Fallback when running outside Tauri native shell
    }

    if (
      typeof navigator !== "undefined" &&
      navigator.mediaDevices &&
      navigator.mediaDevices.enumerateDevices
    ) {
      try {
        const list = await navigator.mediaDevices.enumerateDevices();
        const audioOutputs = list.filter((d) => d.kind === "audiooutput");
        if (audioOutputs.length > 0) {
          setEndpoints((prev) => {
            const selectedIds = new Set(
              prev.filter((p) => p.isSelected).map((p) => p.id)
            );
            return audioOutputs.map((out, idx) => {
              const id = out.deviceId || `default_${idx}`;
              const label =
                out.label && out.label.trim().length > 0
                  ? out.label
                  : idx === 0
                  ? "Loudspeakers"
                  : `Audio Output ${idx + 1}`;
              const isHeadphone =
                label.toLowerCase().includes("headphone") ||
                label.toLowerCase().includes("earbud") ||
                label.toLowerCase().includes("bluetooth");
              return {
                id,
                name: label,
                description: isHeadphone
                  ? "Stereo headphone endpoint"
                  : "System stereo speaker endpoint",
                isDefault: idx === 0,
                isHeadphone,
                isSelected:
                  selectedIds.size > 0 ? selectedIds.has(id) : idx === 0,
              };
            });
          });
          return;
        }
      } catch {
        // Ignore permission blocks
      }
    }

    setEndpoints((prev) =>
      prev.length > 0
        ? prev
        : [
            {
              id: "default_wasapi_render",
              name: "Loudspeakers",
              description: "System default stereo output",
              isDefault: true,
              isHeadphone: false,
              isSelected: true,
            },
          ]
    );
  }, []);

  useEffect(() => {
    fetchRealHardwareDevices();

    let unlistenFn: (() => void) | undefined;
    listen("wasapi-devices-changed", () => {
      fetchRealHardwareDevices();
    })
      .then((unlisten) => {
        unlistenFn = unlisten;
      })
      .catch(() => {});

    return () => {
      if (unlistenFn) unlistenFn();
    };
  }, [fetchRealHardwareDevices]);

  // Synchronize DSP parameters with the Rust WASAPI engine whenever settings change
  const activeProfile = profiles[selectedMode];
  const editingProfile = profiles[editingMode];

  useEffect(() => {
    const baseBands =
      activeProfile.preset === "MANUAL"
        ? activeProfile.manualBandsDb
        : PRESET_CURVES[activeProfile.preset];

    const effectiveBands = baseBands.map((db, idx) => {
      const norm = (idx - 4.5) / 4.5;
      const tilt =
        norm < 0
          ? -norm * activeProfile.macroBassTiltDb
          : norm * activeProfile.macroTrebleTiltDb;
      return Math.max(-10, Math.min(10, db + tilt));
    });

    invoke("update_dsp_config", {
      masterEnabled: useAudioEffects,
      mode: selectedMode,
      bandsDb: effectiveBands,
      surroundVirtualiser:
        activeProfile.surroundVirtualiser || selectedMode === "SPATIAL_AUDIO",
      volumeLeveller: activeProfile.volumeLeveller,
    }).catch(() => {});
  }, [useAudioEffects, selectedMode, activeProfile]);

  const handleToggleEndpoint = async (deviceId: string) => {
    setEndpoints((prev) => {
      const toggled = prev.map((ep) =>
        ep.id === deviceId ? { ...ep, isSelected: !ep.isSelected } : ep
      );
      const anySelected = toggled.some((ep) => ep.isSelected);
      const safe = anySelected
        ? toggled
        : toggled.map((ep, i) => (i === 0 ? { ...ep, isSelected: true } : ep));

      const selectedIds = safe.filter((ep) => ep.isSelected).map((ep) => ep.id);
      invoke("set_selected_wasapi_devices", { deviceIds: selectedIds }).catch(
        () => {}
      );
      return safe;
    });
  };

  const handleSelectMode = (mode: AudioModeId) => {
    setSelectedMode(mode);
    setEditingMode(mode);
  };

  const handleOpenEditor = (mode: AudioModeId) => {
    setSelectedMode(mode);
    setEditingMode(mode);
    setMobileScreen("EDITOR");
  };

  const handleSelectPreset = (preset: EqualiserPresetId) => {
    setProfiles((prev) => {
      const current = prev[editingMode];
      return {
        ...prev,
        [editingMode]: {
          ...current,
          preset,
          manualBandsDb:
            preset === "MANUAL"
              ? current.manualBandsDb
              : [...PRESET_CURVES[preset]],
          macroBassTiltDb: 0,
          macroTrebleTiltDb: 0,
        },
      };
    });
  };

  const handleUpdateBandGain = (bandIndex: number, gainDb: number) => {
    const clamped = Math.round(Math.max(-10, Math.min(10, gainDb)) * 10) / 10;
    setProfiles((prev) => {
      const current = prev[editingMode];
      const nextBands = [...current.manualBandsDb];
      nextBands[bandIndex] = clamped;
      return {
        ...prev,
        [editingMode]: {
          ...current,
          preset: "MANUAL",
          manualBandsDb: nextBands,
        },
      };
    });
  };

  const handleToggleSurround = (enabled: boolean) => {
    setProfiles((prev) => ({
      ...prev,
      [editingMode]: {
        ...prev[editingMode],
        surroundVirtualiser: enabled,
      },
    }));
  };

  const handleToggleVolumeLeveller = (enabled: boolean) => {
    setProfiles((prev) => ({
      ...prev,
      [editingMode]: {
        ...prev[editingMode],
        volumeLeveller: enabled,
      },
    }));
  };

  const handleResetProfile = () => {
    setProfiles((prev) => ({
      ...prev,
      [editingMode]: { ...INITIAL_PROFILES[editingMode] },
    }));
  };

  // ============================================================================
  // WIDE DESKTOP VIEW (>= 768px): Two-Column Responsive Dashboard
  // ============================================================================
  if (isWideDesktop) {
    return (
      <div className="min-h-screen bg-[#0B0E14] text-[#F1F5F9] flex flex-col">
        {/* Top Desktop Title Bar */}
        <header className="h-14 border-b border-[#1E2638] px-6 flex items-center justify-between bg-[#0B0E14]/95 sticky top-0 z-20">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded-lg bg-[#141923] border border-[#1E2638] flex items-center justify-center">
              <div className="w-3.5 h-3.5 rounded-full bg-[#7BA7FF]" />
            </div>
            <span className="text-lg font-semibold tracking-tight text-[#F1F5F9]">
              Dolby Atmos
            </span>
          </div>
          <button
            onClick={handleResetProfile}
            className="flex items-center gap-2 text-sm text-[#94A3B8] hover:text-[#F1F5F9] px-3 py-1.5 rounded-lg hover:bg-[#141923] transition-colors"
            title="Reset current profile to default"
          >
            <RotateCcw className="w-4 h-4 text-[#7BA7FF]" />
            <span>Reset profile</span>
          </button>
        </header>

        {/* Main Two-Column PC Grid */}
        <main className="flex-1 grid grid-cols-[400px_1fr] max-w-[1440px] w-full mx-auto">
          {/* LEFT COLUMN: Master Enable Card + Output Device Selector + Audio Profiles */}
          <aside className="border-r border-[#1E2638] p-6 overflow-y-auto space-y-7">
            {/* Hero Status Card */}
            <div className="rounded-3xl bg-gradient-to-b from-[#1B253B] to-[#141923] border border-[#1E2638] p-5 shadow-lg">
              <h2 className="text-2xl font-bold text-[#F1F5F9]">Dolby Atmos</h2>
              <p className="text-xs text-[#94A3B8] mt-1.5 leading-relaxed">
                Experience breakthrough sound that gives you greater control of
                what you hear
              </p>

              {/* Rounded White Pill Master Toggle matching mobile Screen 1 */}
              <div
                onClick={() => setUseAudioEffects(!useAudioEffects)}
                className="mt-4 bg-[#F4F6FA] text-[#11141A] rounded-full px-5 py-3 flex items-center justify-between cursor-pointer select-none transition-transform active:scale-[0.99]"
              >
                <span className="font-medium text-sm">Use Audio effects</span>
                <DolbySwitch
                  checked={useAudioEffects}
                  onChange={setUseAudioEffects}
                />
              </div>
            </div>

            {/* Output Device Selector (Multi-Select Active Windows WASAPI Endpoints) */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm font-medium text-[#7BA7FF]">
                  Loudspeakers
                </span>
              </div>

              <div className="space-y-1">
                {endpoints.map((ep) => (
                  <div
                    key={ep.id}
                    onClick={() => handleToggleEndpoint(ep.id)}
                    className="flex items-center gap-3.5 py-2.5 px-2 rounded-xl hover:bg-[#141923] cursor-pointer transition-colors"
                  >
                    <SelectionDot selected={ep.isSelected} />
                    <div className="flex-1 min-w-0">
                      <div className="text-base font-medium text-[#F1F5F9] truncate">
                        {ep.name}
                      </div>
                      <div className="text-xs text-[#94A3B8] truncate">
                        {ep.description}
                      </div>
                    </div>
                  </div>
                ))}

                <button
                  onClick={fetchRealHardwareDevices}
                  className="flex items-center gap-3 py-2.5 px-2 w-full text-left rounded-xl hover:bg-[#141923] text-[#7BA7FF] text-sm font-medium transition-colors"
                >
                  <div className="w-6 h-6 flex items-center justify-center">
                    <Plus className="w-4 h-4" />
                  </div>
                  <span>+ Add device</span>
                </button>
              </div>
            </div>

            {/* Spatial Audio Section */}
            <div>
              <div className="text-sm font-medium text-[#F1F5F9] mb-2">
                Spatial audio
              </div>
              {AUDIO_MODES.filter((m) => m.section === "SPATIAL").map((mode) => (
                <ProfileRow
                  key={mode.id}
                  mode={mode}
                  selected={selectedMode === mode.id}
                  onSelect={() => handleSelectMode(mode.id)}
                  onEdit={undefined}
                />
              ))}
            </div>

            {/* Stereo Audio Section */}
            <div>
              <div className="text-sm font-medium text-[#F1F5F9] mb-2">
                Stereo audio
              </div>
              <div className="space-y-1">
                {AUDIO_MODES.filter((m) => m.section === "STEREO").map(
                  (mode) => (
                    <ProfileRow
                      key={mode.id}
                      mode={mode}
                      selected={selectedMode === mode.id}
                      onSelect={() => handleSelectMode(mode.id)}
                      onEdit={
                        mode.hasEditor
                          ? () => handleSelectMode(mode.id)
                          : undefined
                      }
                    />
                  )
                )}
              </div>
            </div>
          </aside>

          {/* RIGHT COLUMN: Dynamic Editor Pane (Bezier Curve, 10-Band Sliders / Presets, Toggles) */}
          <section className="p-8 overflow-y-auto">
            <RightEditorPane
              mode={editingMode}
              profile={editingProfile}
              onSelectPreset={handleSelectPreset}
              onUpdateBandGain={handleUpdateBandGain}
              onToggleSurround={handleToggleSurround}
              onToggleVolumeLeveller={handleToggleVolumeLeveller}
              onResetProfile={handleResetProfile}
            />
          </section>
        </main>
      </div>
    );
  }

  // ============================================================================
  // COMPACT / MOBILE VIEW (< 768px): Single-Column Stack Matching Reference Screenshots
  // ============================================================================
  if (mobileScreen === "HERO") {
    return (
      <div className="min-h-screen bg-[#0B0E14] text-[#F1F5F9] flex flex-col p-3">
        <div className="flex-1 rounded-[32px] bg-gradient-to-b from-[#17233B] via-[#121826] to-[#0E131D] border border-[#1E2638] p-7 flex flex-col justify-between relative overflow-hidden">
          <div className="mt-4">
            <h1 className="text-4xl font-bold tracking-tight text-[#F1F5F9]">
              Dolby Atmos
            </h1>
            <p className="text-base text-[#94A3B8] mt-3 leading-relaxed max-w-md">
              Experience breakthrough sound that gives you greater control of
              what you hear
            </p>

            <div
              onClick={() => setUseAudioEffects(!useAudioEffects)}
              className="mt-7 bg-[#F4F6FA] text-[#11141A] rounded-[28px] px-6 py-4 flex items-center justify-between cursor-pointer select-none shadow-md"
            >
              <span className="text-lg font-medium">Use Audio effects</span>
              <DolbySwitch
                checked={useAudioEffects}
                onChange={setUseAudioEffects}
              />
            </div>
          </div>

          {/* Center Concentric Dolby Aura Graphic */}
          <div className="my-12 flex items-center justify-center">
            <div className="w-56 h-56 rounded-full border border-[#7BA7FF]/25 flex items-center justify-center relative">
              <div className="w-40 h-40 rounded-full border border-[#7BA7FF]/40 flex items-center justify-center">
                <div className="w-24 h-24 rounded-2xl bg-[#141923]/90 border border-[#7BA7FF]/50 flex items-center justify-center shadow-[0_0_40px_rgba(123,167,255,0.35)]">
                  <div className="w-10 h-10 rounded-full bg-[#7BA7FF]" />
                </div>
              </div>
            </div>
          </div>

          {/* Bottom Bar: Left Gear Icon (Opens Settings) + Right Learn More */}
          <div className="flex items-center justify-between pt-4">
            <button
              onClick={() => setMobileScreen("SETTINGS")}
              aria-label="Open Dolby Atmos Settings"
              className="w-12 h-12 rounded-full flex items-center justify-center hover:bg-white/10 transition-colors"
            >
              <Settings className="w-6 h-6 text-[#F1F5F9]" />
            </button>

            <button
              onClick={() => setMobileScreen("SETTINGS")}
              className="flex items-center gap-2 text-[#7BA7FF] text-sm font-medium px-3 py-2 rounded-lg hover:bg-white/5"
            >
              <ArrowRight className="w-4 h-4" />
              <span>Learn more</span>
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (mobileScreen === "SETTINGS") {
    return (
      <div className="min-h-screen bg-[#0B0E14] text-[#F1F5F9] flex flex-col">
        <div className="h-14 px-4 flex items-center">
          <button
            onClick={() => setMobileScreen("HERO")}
            className="w-12 h-12 rounded-full flex items-center justify-center hover:bg-[#141923]"
          >
            <ArrowLeft className="w-6 h-6 text-[#F1F5F9]" />
          </button>
        </div>

        <div className="px-6 pb-10 space-y-6">
          <h1 className="text-4xl font-semibold tracking-tight pt-2 pb-2">
            Settings
          </h1>

          {/* Loudspeakers / Output Devices */}
          <div>
            <div className="text-sm font-medium text-[#7BA7FF] mb-2">
              Loudspeakers
            </div>
            <div className="space-y-1">
              {endpoints.map((ep) => (
                <div
                  key={ep.id}
                  onClick={() => handleToggleEndpoint(ep.id)}
                  className="flex items-center gap-4 py-3 cursor-pointer"
                >
                  <SelectionDot selected={ep.isSelected} />
                  <div className="flex-1">
                    <div className="text-xl font-medium text-[#F1F5F9]">
                      {ep.name}
                    </div>
                    <div className="text-sm text-[#94A3B8]">
                      {ep.description}
                    </div>
                  </div>
                </div>
              ))}

              <button
                onClick={fetchRealHardwareDevices}
                className="flex items-center gap-4 py-2.5 text-[#7BA7FF] text-base font-medium"
              >
                <div className="w-6 h-6 flex items-center justify-center">
                  <Plus className="w-5 h-5" />
                </div>
                <span>+ Add device</span>
              </button>
            </div>
          </div>

          {/* Spatial audio */}
          <div>
            <div className="text-sm font-medium text-[#F1F5F9] mb-2">
              Spatial audio
            </div>
            {AUDIO_MODES.filter((m) => m.section === "SPATIAL").map((mode) => (
              <ProfileRow
                key={mode.id}
                mode={mode}
                selected={selectedMode === mode.id}
                onSelect={() => handleSelectMode(mode.id)}
                onEdit={undefined}
              />
            ))}
          </div>

          {/* Stereo audio */}
          <div>
            <div className="text-sm font-medium text-[#F1F5F9] mb-2">
              Stereo audio
            </div>
            <div className="space-y-1">
              {AUDIO_MODES.filter((m) => m.section === "STEREO").map((mode) => (
                <ProfileRow
                  key={mode.id}
                  mode={mode}
                  selected={selectedMode === mode.id}
                  onSelect={() => handleSelectMode(mode.id)}
                  onEdit={
                    mode.hasEditor ? () => handleOpenEditor(mode.id) : undefined
                  }
                />
              ))}
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#0B0E14] text-[#F1F5F9] flex flex-col">
      <div className="h-14 px-4 flex items-center justify-between">
        <button
          onClick={() => setMobileScreen("SETTINGS")}
          className="w-12 h-12 rounded-full flex items-center justify-center hover:bg-[#141923]"
        >
          <ArrowLeft className="w-6 h-6 text-[#F1F5F9]" />
        </button>
        <button
          onClick={handleResetProfile}
          className="w-12 h-12 rounded-full flex items-center justify-center hover:bg-[#141923]"
          title="Reset to default"
        >
          <RotateCcw className="w-5 h-5 text-[#F1F5F9]" />
        </button>
      </div>

      <div className="px-6 pb-12">
        <RightEditorPane
          mode={editingMode}
          profile={editingProfile}
          onSelectPreset={handleSelectPreset}
          onUpdateBandGain={handleUpdateBandGain}
          onToggleSurround={handleToggleSurround}
          onToggleVolumeLeveller={handleToggleVolumeLeveller}
          onResetProfile={handleResetProfile}
        />
      </div>
    </div>
  );
}

// ============================================================================
// Subcomponents (Frequency Response Curve, 10-Band Vertical Pill Sliders, Rows)
// ============================================================================

function RightEditorPane({
  mode,
  profile,
  onSelectPreset,
  onUpdateBandGain,
  onToggleSurround,
  onToggleVolumeLeveller,
}: {
  mode: AudioModeId;
  profile: ProfileSettings;
  onSelectPreset: (p: EqualiserPresetId) => void;
  onUpdateBandGain: (idx: number, db: number) => void;
  onToggleSurround: (enabled: boolean) => void;
  onToggleVolumeLeveller: (enabled: boolean) => void;
  onResetProfile: () => void;
}) {
  const modeMeta =
    AUDIO_MODES.find((m) => m.id === mode) || AUDIO_MODES[2];
  const isManual =
    mode === "CUSTOM" && profile.preset === "MANUAL";

  const activeBands =
    profile.preset === "MANUAL"
      ? profile.manualBandsDb
      : PRESET_CURVES[profile.preset];

  const presets: { id: EqualiserPresetId; label: string }[] =
    mode === "CUSTOM"
      ? [
          { id: "MANUAL", label: "Manual" },
          { id: "BRILLIANT_TREBLE", label: "Brilliant treble" },
          { id: "BASS_BOOST", label: "Bass boost" },
          { id: "VOCAL_BOOST", label: "Vocal boost" },
          { id: "FLAT", label: "Flat" },
        ]
      : [
          { id: "BRILLIANT_TREBLE", label: "Brilliant treble" },
          { id: "BASS_BOOST", label: "Bass boost" },
          { id: "VOCAL_BOOST", label: "Vocal boost" },
          { id: "FLAT", label: "Flat" },
        ];

  return (
    <div className="max-w-3xl space-y-8">
      <div>
        <h1 className="text-4xl font-semibold tracking-tight text-[#F1F5F9]">
          {modeMeta.title} settings
        </h1>
        <p className="text-sm text-[#94A3B8] mt-1">{modeMeta.subtitle}</p>
      </div>

      {/* Top Frequency Response Graph OR 10-Band Vertical Sliders */}
      {isManual ? (
        <TenBandVerticalEqualiser
          bandsDb={profile.manualBandsDb}
          onChangeBand={onUpdateBandGain}
        />
      ) : (
        <CubicBezierFrequencyGraph bandsDb={activeBands} />
      )}

      {/* Choose equaliser Radio List */}
      <div>
        <div className="text-sm font-medium text-[#7BA7FF] mb-3">
          Choose equaliser
        </div>
        <div className="space-y-1">
          {presets.map((preset) => (
            <div
              key={preset.id}
              onClick={() => onSelectPreset(preset.id)}
              className="flex items-center gap-4 py-3 px-2 rounded-xl hover:bg-[#141923] cursor-pointer transition-colors"
            >
              <SelectionDot selected={profile.preset === preset.id} />
              <span className="text-lg font-medium text-[#F1F5F9]">
                {preset.label}
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* Profile settings Switches */}
      <div>
        <div className="text-sm font-medium text-[#7BA7FF] mb-3">
          Profile settings
        </div>
        <div className="space-y-4">
          <div
            onClick={() => onToggleSurround(!profile.surroundVirtualiser)}
            className="flex items-center justify-between py-3 px-4 rounded-2xl bg-[#141923] border border-[#1E2638] cursor-pointer"
          >
            <div className="pr-4">
              <div className="text-lg font-medium text-[#F1F5F9]">
                Surround virtualiser
              </div>
              <div className="text-sm text-[#94A3B8] mt-0.5">
                Make sound seem like it comes from all around you
              </div>
            </div>
            <DolbySwitch
              checked={profile.surroundVirtualiser}
              onChange={onToggleSurround}
            />
          </div>

          <div
            onClick={() => onToggleVolumeLeveller(!profile.volumeLeveller)}
            className="flex items-center justify-between py-3 px-4 rounded-2xl bg-[#141923] border border-[#1E2638] cursor-pointer"
          >
            <div className="pr-4">
              <div className="text-lg font-medium text-[#F1F5F9]">
                Volume leveller
              </div>
              <div className="text-sm text-[#94A3B8] mt-0.5">
                Keep the volume at a consistent level across different types of
                content and apps
              </div>
            </div>
            <DolbySwitch
              checked={profile.volumeLeveller}
              onChange={onToggleVolumeLeveller}
            />
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * Animated SVG Cubic Bézier Frequency Response Graph (-10 dB to +10 dB, Bass to Treble)
 * matching Screenshots 3 & 4.
 */
function CubicBezierFrequencyGraph({ bandsDb }: { bandsDb: number[] }) {
  const width = 640;
  const height = 210;
  const padLeft = 56;
  const padRight = 24;
  const padTop = 20;
  const padBottom = 36;

  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  const points = bandsDb.map((db, i) => {
    const x = padLeft + (i / (bandsDb.length - 1)) * plotW;
    const clamped = Math.max(-10, Math.min(10, db));
    const normY = (10 - clamped) / 20; // +10dB -> 0, -10dB -> 1
    const y = padTop + normY * plotH;
    return { x, y };
  });

  // Construct smooth Catmull-Rom / Cubic Bézier SVG path
  let pathD = "";
  if (points.length > 0) {
    pathD = `M ${points[0].x.toFixed(1)} ${points[0].y.toFixed(1)}`;
    for (let i = 0; i < points.length - 1; i++) {
      const p0 = points[Math.max(0, i - 1)];
      const p1 = points[i];
      const p2 = points[i + 1];
      const p3 = points[Math.min(points.length - 1, i + 2)];

      const cp1x = p1.x + (p2.x - p0.x) / 6;
      const cp1y = p1.y + (p2.y - p0.y) / 6;
      const cp2x = p2.x - (p3.x - p1.x) / 6;
      const cp2y = p2.y - (p3.y - p1.y) / 6;

      pathD += ` C ${cp1x.toFixed(1)} ${cp1y.toFixed(1)}, ${cp2x.toFixed(
        1
      )} ${cp2y.toFixed(1)}, ${p2.x.toFixed(1)} ${p2.y.toFixed(1)}`;
    }
  }

  const fillD = `${pathD} L ${points[points.length - 1].x.toFixed(1)} ${(
    padTop + plotH
  ).toFixed(1)} L ${points[0].x.toFixed(1)} ${(padTop + plotH).toFixed(1)} Z`;

  return (
    <div className="bg-[#141923] border border-[#1E2638] rounded-3xl p-5">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full h-52 overflow-visible"
      >
        <defs>
          <linearGradient id="dolbyCurveFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#7BA7FF" stopOpacity="0.32" />
            <stop offset="100%" stopColor="#7BA7FF" stopOpacity="0.0" />
          </linearGradient>
        </defs>

        {/* Y-Axis Labels (+10 db, 0 db, -10 db) */}
        <text
          x={0}
          y={padTop + 5}
          fill="#94A3B8"
          fontSize="12"
          fontFamily="sans-serif"
        >
          +10 db
        </text>
        <text
          x={12}
          y={padTop + plotH / 2 + 4}
          fill="#94A3B8"
          fontSize="12"
          fontFamily="sans-serif"
        >
          0 db
        </text>
        <text
          x={2}
          y={padTop + plotH + 4}
          fill="#94A3B8"
          fontSize="12"
          fontFamily="sans-serif"
        >
          -10 db
        </text>

        {/* 4 Vertical Grid Dividers matching Screenshots 3 & 4 */}
        {[0, 0.333, 0.666, 1].map((frac, idx) => {
          const x = padLeft + frac * plotW;
          return (
            <line
              key={idx}
              x1={x}
              y1={padTop}
              x2={x}
              y2={padTop + plotH}
              stroke="#5E636B"
              strokeWidth="1"
              strokeOpacity="0.55"
            />
          );
        })}

        {/* Gradient Under Curve */}
        <path d={fillD} fill="url(#dolbyCurveFill)" />

        {/* Smooth Cubic Bézier Spline */}
        <path
          d={pathD}
          fill="none"
          stroke="#7BA7FF"
          strokeWidth="3"
          strokeLinecap="round"
        />

        {/* Bass / Treble X-Axis Labels */}
        <text
          x={padLeft}
          y={height - 6}
          fill="#94A3B8"
          fontSize="12"
          fontFamily="sans-serif"
        >
          Bass
        </text>
        <text
          x={padLeft + plotW}
          y={height - 6}
          textAnchor="end"
          fill="#94A3B8"
          fontSize="12"
          fontFamily="sans-serif"
        >
          Treble
        </text>
      </svg>
    </div>
  );
}

/**
 * 10-Band Vertical Pill Sliders (31.5 Hz to 16 kHz) matching Screenshots 6 & 7.
 */
function TenBandVerticalEqualiser({
  bandsDb,
  onChangeBand,
}: {
  bandsDb: number[];
  onChangeBand: (index: number, gainDb: number) => void;
}) {
  return (
    <div className="bg-[#141923] border border-[#1E2638] rounded-3xl p-5">
      <div className="flex items-stretch justify-between gap-2 h-52">
        {bandsDb.map((db, idx) => (
          <VerticalPillSlider
            key={idx}
            freqLabel={ISO_10_BAND_LABELS[idx]}
            gainDb={db}
            onChange={(val) => onChangeBand(idx, val)}
          />
        ))}
      </div>
      <div className="flex justify-between text-xs text-[#94A3B8] mt-3 px-1">
        <span>Bass</span>
        <span>Treble</span>
      </div>
    </div>
  );
}

function VerticalPillSlider({
  freqLabel,
  gainDb,
  onChange,
}: {
  freqLabel: string;
  gainDb: number;
  onChange: (db: number) => void;
}) {
  const trackRef = useRef<HTMLDivElement | null>(null);

  const updateFromClientY = (clientY: number) => {
    const rect = trackRef.current?.getBoundingClientRect();
    if (!rect) return;
    const relY = Math.max(0, Math.min(rect.height, clientY - rect.top));
    const norm = 1 - relY / rect.height; // 0 bottom (-10 dB), 1 top (+10 dB)
    const db = -10 + norm * 20;
    onChange(db);
  };

  const fillPercent = ((gainDb + 10) / 20) * 100;

  return (
    <div className="flex flex-col items-center flex-1 select-none">
      <div
        ref={trackRef}
        onPointerDown={(e) => {
          e.currentTarget.setPointerCapture(e.pointerId);
          updateFromClientY(e.clientY);
        }}
        onPointerMove={(e) => {
          if (e.buttons === 1) {
            updateFromClientY(e.clientY);
          }
        }}
        className="w-3.5 flex-1 rounded-full bg-[#DCDAD5] relative overflow-hidden cursor-ns-resize"
        title={`${freqLabel} Hz: ${gainDb > 0 ? "+" : ""}${gainDb.toFixed(1)} dB`}
      >
        {/* Active Blue Fill Track */}
        <div
          style={{ height: `${fillPercent}%` }}
          className="absolute bottom-0 left-0 right-0 bg-[#004A99] rounded-b-full transition-all duration-75"
        />
        {/* Light-Blue Thumb Cap */}
        <div
          style={{
            bottom: `calc(${fillPercent}% - 7px)`,
          }}
          className="w-3.5 h-3.5 rounded-full bg-[#7BA7FF] absolute left-0 shadow-sm pointer-events-none"
        />
      </div>
      <span className="text-[11px] text-[#94A3B8] mt-2">{freqLabel}</span>
    </div>
  );
}

function ProfileRow({
  mode,
  selected,
  onSelect,
  onEdit,
}: {
  mode: { id: AudioModeId; title: string; subtitle: string };
  selected: boolean;
  onSelect: () => void;
  onEdit?: () => void;
}) {
  return (
    <div
      onClick={onSelect}
      className="flex items-center gap-3.5 py-3 px-2 rounded-xl hover:bg-[#141923] cursor-pointer transition-colors"
    >
      <SelectionDot selected={selected} />
      <div className="flex-1 min-w-0 pr-2">
        <div className="text-lg font-medium text-[#F1F5F9]">{mode.title}</div>
        <div className="text-xs text-[#94A3B8] leading-relaxed">
          {mode.subtitle}
        </div>
      </div>

      {onEdit && (
        <div className="flex items-center gap-2">
          <div className="w-[1px] h-8 bg-[#3C4043]" />
          <button
            onClick={(e) => {
              e.stopPropagation();
              onEdit();
            }}
            aria-label={`Configure ${mode.title}`}
            className="w-10 h-10 rounded-full flex items-center justify-center hover:bg-white/10 text-[#8AB4F8]"
          >
            <Pencil className="w-4 h-4" />
          </button>
        </div>
      )}
    </div>
  );
}

function SelectionDot({ selected }: { selected: boolean }) {
  return (
    <div
      className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
        selected ? "border-[#7BA7FF]" : "border-[#DADCE0]"
      }`}
    >
      {selected && <div className="w-2.5 h-2.5 rounded-full bg-[#7BA7FF]" />}
    </div>
  );
}

function DolbySwitch({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (val: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={(e) => {
        e.stopPropagation();
        onChange(!checked);
      }}
      className={`w-12 h-7 rounded-full p-1 transition-colors flex items-center ${
        checked ? "bg-[#1B72E8]" : "bg-[#3C4043]"
      }`}
    >
      <div
        className={`w-5 h-5 rounded-full bg-white shadow transform transition-transform ${
          checked ? "translate-x-5" : "translate-x-0"
        }`}
      />
    </button>
  );
}
