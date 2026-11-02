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
  Volume2,
  Activity,
  Waves,
  BarChart3,
  Sparkles,
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
  const [liveRms, setLiveRms] = useState<number>(0);
  const [livePeak, setLivePeak] = useState<number>(0);

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
        setEndpoints(
          devices.map((d) => ({
            ...d,
            relativeGain:
              typeof d.relativeGain === "number" ? d.relativeGain : 1.0,
          }))
        );
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
            const prevGains = new Map(
              prev.map((p) => [p.id, p.relativeGain ?? 1.0])
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
                relativeGain: prevGains.get(id) ?? 1.0,
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
              relativeGain: 1.0,
            },
          ]
    );
  }, []);

  useEffect(() => {
    fetchRealHardwareDevices();

    let unlistenDevices: (() => void) | undefined;
    let unlistenVolume: (() => void) | undefined;
    let unlistenAudioLevel: (() => void) | undefined;

    listen("wasapi-devices-changed", () => {
      fetchRealHardwareDevices();
    })
      .then((unlisten) => {
        unlistenDevices = unlisten;
      })
      .catch(() => {});

    // Live bidirectional volume synchronization with Windows
    listen<{ id: string; volume: number }>("wasapi-volume-changed", (event) => {
      if (!event.payload || !event.payload.id) return;
      const vol = Math.max(0, Math.min(1, event.payload.volume));
      setEndpoints((prev) =>
        prev.map((ep) =>
          ep.id === event.payload.id ? { ...ep, relativeGain: vol } : ep
        )
      );
    })
      .then((unlisten) => {
        unlistenVolume = unlisten;
      })
      .catch(() => {});

    // Live audio telemetry for real-time visualizer animation
    listen<{ rms: number; peak: number }>("wasapi-audio-level", (event) => {
      if (event.payload) {
        setLiveRms(event.payload.rms || 0);
        setLivePeak(event.payload.peak || 0);
      }
    })
      .then((unlisten) => {
        unlistenAudioLevel = unlisten;
      })
      .catch(() => {});

    return () => {
      if (unlistenDevices) unlistenDevices();
      if (unlistenVolume) unlistenVolume();
      if (unlistenAudioLevel) unlistenAudioLevel();
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

  const handleUpdateEndpointRelativeGain = (
    deviceId: string,
    relativeGain: number
  ) => {
    const clamped = Math.max(0, Math.min(1, relativeGain));
    setEndpoints((prev) =>
      prev.map((ep) =>
        ep.id === deviceId ? { ...ep, relativeGain: clamped } : ep
      )
    );
    // Directly adjust the real Windows system volume for this hardware device
    invoke("set_device_relative_gain", {
      deviceId,
      relativeGain: clamped,
    }).catch(() => {});
    invoke("set_device_volume", {
      deviceId,
      volume: clamped,
    }).catch(() => {});
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

            {/* Output Device Selector (Multi-Select Active Windows WASAPI Endpoints + Per-Device Volume Sliders) */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm font-medium text-[#7BA7FF]">
                  Loudspeakers
                </span>
              </div>

              <div className="space-y-1.5">
                {endpoints.map((ep) => (
                  <div
                    key={ep.id}
                    className="rounded-xl hover:bg-[#141923]/60 transition-colors"
                  >
                    <div
                      onClick={() => handleToggleEndpoint(ep.id)}
                      className="flex items-center gap-3.5 py-2.5 px-2 cursor-pointer"
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

                    {ep.isSelected && (
                      <DeviceVolumeSlider
                        gainScalar={ep.relativeGain ?? 1.0}
                        onChange={(val) =>
                          handleUpdateEndpointRelativeGain(ep.id, val)
                        }
                      />
                    )}
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
              useAudioEffects={useAudioEffects}
              liveRms={liveRms}
              livePeak={livePeak}
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
            <div className="space-y-1.5">
              {endpoints.map((ep) => (
                <div key={ep.id} className="py-1">
                  <div
                    onClick={() => handleToggleEndpoint(ep.id)}
                    className="flex items-center gap-4 py-2 cursor-pointer"
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

                  {ep.isSelected && (
                    <DeviceVolumeSlider
                      gainScalar={ep.relativeGain ?? 1.0}
                      onChange={(val) =>
                        handleUpdateEndpointRelativeGain(ep.id, val)
                      }
                    />
                  )}
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
          useAudioEffects={useAudioEffects}
          liveRms={liveRms}
          livePeak={livePeak}
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
// Subcomponents (Per-Device Volume Slider, Frequency Curve, 10-Band EQ, Rows)
// ============================================================================

/**
 * Sleek horizontal per-device relative volume slider (0% to 100%, default 100%)
 * rendered directly below each checked output device.
 */
function DeviceVolumeSlider({
  gainScalar,
  onChange,
}: {
  gainScalar: number;
  onChange: (val: number) => void;
}) {
  const trackRef = useRef<HTMLDivElement | null>(null);
  const clamped = Math.max(0, Math.min(1, gainScalar));
  const percent = Math.round(clamped * 100);

  const updateFromClientX = (clientX: number) => {
    const rect = trackRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0) return;
    const relX = Math.max(0, Math.min(rect.width, clientX - rect.left));
    const normalized = Math.max(0, Math.min(1, relX / rect.width));
    onChange(normalized);
  };

  return (
    <div
      onClick={(e) => e.stopPropagation()}
      className="pl-10 pr-2 pb-2.5 pt-1 flex items-center gap-3 select-none"
    >
      <Volume2 className="w-3.5 h-3.5 text-[#94A3B8] shrink-0" />
      <div
        ref={trackRef}
        onPointerDown={(e) => {
          e.stopPropagation();
          e.currentTarget.setPointerCapture(e.pointerId);
          updateFromClientX(e.clientX);
        }}
        onPointerMove={(e) => {
          if (e.buttons === 1) {
            updateFromClientX(e.clientX);
          }
        }}
        className="flex-1 h-3 flex items-center relative cursor-ew-resize group"
        title={`Device system volume: ${percent}%`}
      >
        {/* Background Track */}
        <div className="w-full h-1.5 rounded-full bg-[#141923] border border-[#1E2638] overflow-hidden relative">
          <div
            style={{ width: `${percent}%` }}
            className="h-full bg-[#7BA7FF] rounded-full transition-all duration-75"
          />
        </div>
        {/* Circular Thumb Handle matching Windows 11 Sound Settings */}
        <div
          style={{ left: `calc(${percent}% - 6px)` }}
          className="absolute w-3 h-3 rounded-full bg-white border border-[#7BA7FF] shadow-[0_0_6px_rgba(123,167,255,0.6)] group-hover:scale-125 transition-transform pointer-events-none"
        />
      </div>
      <span className="text-xs font-semibold text-[#F1F5F9] w-9 text-right tabular-nums">
        {percent}%
      </span>
    </div>
  );
}

function RightEditorPane({
  mode,
  profile,
  useAudioEffects = true,
  liveRms = 0,
  livePeak = 0,
  onSelectPreset,
  onUpdateBandGain,
  onToggleSurround,
  onToggleVolumeLeveller,
}: {
  mode: AudioModeId;
  profile: ProfileSettings;
  useAudioEffects?: boolean;
  liveRms?: number;
  livePeak?: number;
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

      {/* Top Frequency Response Visualizer OR 10-Band Vertical Sliders */}
      {isManual ? (
        <TenBandVerticalEqualiser
          bandsDb={profile.manualBandsDb}
          onChangeBand={onUpdateBandGain}
        />
      ) : (
        <ResponsiveDolbyVisualizer
          bandsDb={activeBands}
          liveRms={liveRms}
          livePeak={livePeak}
          useAudioEffects={useAudioEffects}
          presetName={profile.preset}
        />
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

type VisualizerMode = "WAVE" | "RTA" | "CURVE";

const ISO_BAND_DESCRIPTIONS = [
  "Sub-Bass Impact",
  "Bass Foundation",
  "Bass Warmth & Punch",
  "Lower Midbody",
  "Vocal Body & Warmth",
  "Voice Formant & Consonants (Enhanced)",
  "Dialogue Intelligibility & Clarity (Enhanced)",
  "Presence & Definition",
  "High Treble Clarity",
  "Air & Sibilance",
];

/**
 * High-Performance Responsive Dolby Atmos Audio Visualizer
 * Combines authentic Cubic Bézier frequency curves with real-time dynamic audio waves,
 * 24-band spectrum analyzer bars, voice intelligibility indicator, and interactive frequency HUD.
 */
function ResponsiveDolbyVisualizer({
  bandsDb,
  liveRms = 0,
  livePeak = 0,
  useAudioEffects = true,
  presetName,
}: {
  bandsDb: number[];
  liveRms?: number;
  livePeak?: number;
  useAudioEffects?: boolean;
  presetName?: EqualiserPresetId;
}) {
  const [visMode, setVisMode] = useState<VisualizerMode>("WAVE");
  const [hoverData, setHoverData] = useState<{
    x: number;
    y: number;
    freqLabel: string;
    db: number;
    role: string;
  } | null>(null);

  const [animTime, setAnimTime] = useState<number>(0);
  const animFrameRef = useRef<number>(0);
  const smoothAmpsRef = useRef<number[]>(new Array(24).fill(0.08));

  // 60 FPS animation loop for organic wave motion and reactive spectrum bars
  useEffect(() => {
    let lastT = performance.now();
    const update = (now: number) => {
      const dt = (now - lastT) / 1000;
      lastT = now;
      setAnimTime((t) => t + dt);

      // Smooth decay for spectrum bars
      const energyMultiplier = useAudioEffects ? Math.max(0.05, liveRms * 3.5) : 0.02;
      for (let i = 0; i < smoothAmpsRef.current.length; i++) {
        const normX = i / (smoothAmpsRef.current.length - 1);
        const bandIdx = Math.min(bandsDb.length - 1, Math.floor(normX * bandsDb.length));
        const eqBoost = Math.max(0, (bandsDb[bandIdx] + 10) / 20);
        // Organic sinusoidal motion + live audio RMS reaction
        const target =
          Math.sin(now * 0.003 + i * 0.45) * 0.08 +
          Math.cos(now * 0.0045 + i * 0.3) * 0.05 +
          0.12 +
          energyMultiplier * eqBoost * 0.85;
        smoothAmpsRef.current[i] =
          smoothAmpsRef.current[i] * 0.82 + Math.max(0.02, Math.min(1.0, target)) * 0.18;
      }

      animFrameRef.current = requestAnimationFrame(update);
    };

    animFrameRef.current = requestAnimationFrame(update);
    return () => cancelAnimationFrame(animFrameRef.current);
  }, [liveRms, bandsDb, useAudioEffects]);

  const width = 640;
  const height = 210;
  const padLeft = 56;
  const padRight = 24;
  const padTop = 20;
  const padBottom = 36;

  const plotW = width - padLeft - padRight;
  const plotH = height - padTop - padBottom;

  // Compute Bézier curve points matching EQ profile
  const points = bandsDb.map((db, i) => {
    const x = padLeft + (i / (bandsDb.length - 1)) * plotW;
    const clamped = Math.max(-10, Math.min(10, db));
    // Subtle breathing pulse on the curve proportional to audio activity
    const pulseOffset =
      useAudioEffects && liveRms > 0.01
        ? Math.sin(animTime * 6 + i) * liveRms * 4.0
        : Math.sin(animTime * 1.5 + i * 0.6) * 0.6;
    const normY = (10 - clamped - pulseOffset) / 20; // +10dB -> 0, -10dB -> 1
    const y = padTop + Math.max(0, Math.min(1, normY)) * plotH;
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

  // Secondary flowing acoustic harmonic waves beneath the curve
  let waveD = "";
  let waveD2 = "";
  const wavePointsCount = 32;
  for (let w = 0; w <= wavePointsCount; w++) {
    const frac = w / wavePointsCount;
    const wx = padLeft + frac * plotW;
    const bandIdx = Math.min(bandsDb.length - 1, Math.floor(frac * bandsDb.length));
    const db = bandsDb[bandIdx];
    const baseNormY = (10 - db) / 20;
    const baseY = padTop + baseNormY * plotH;

    // Primary harmonic wave
    const waveAmp1 = (useAudioEffects ? (liveRms * 16 + 5) : 3) * Math.sin(animTime * 3.8 + frac * Math.PI * 3.2);
    const wy1 = Math.min(padTop + plotH, Math.max(padTop, baseY + 16 + waveAmp1));

    // Secondary counter-phase flowing wave
    const waveAmp2 = (useAudioEffects ? (liveRms * 12 + 3) : 2) * Math.cos(animTime * 2.8 + frac * Math.PI * 2.5 + 1.2);
    const wy2 = Math.min(padTop + plotH, Math.max(padTop, baseY + 24 + waveAmp2));

    if (w === 0) {
      waveD = `M ${wx.toFixed(1)} ${wy1.toFixed(1)}`;
      waveD2 = `M ${wx.toFixed(1)} ${wy2.toFixed(1)}`;
    } else {
      waveD += ` L ${wx.toFixed(1)} ${wy1.toFixed(1)}`;
      waveD2 += ` L ${wx.toFixed(1)} ${wy2.toFixed(1)}`;
    }
  }
  const waveFillD = `${waveD} L ${(padLeft + plotW).toFixed(1)} ${(
    padTop + plotH
  ).toFixed(1)} L ${padLeft.toFixed(1)} ${(padTop + plotH).toFixed(1)} Z`;
  const waveFillD2 = `${waveD2} L ${(padLeft + plotW).toFixed(1)} ${(
    padTop + plotH
  ).toFixed(1)} L ${padLeft.toFixed(1)} ${(padTop + plotH).toFixed(1)} Z`;

  // Voice formant range X boundary: band 5 (1 kHz) to band 7 (4 kHz)
  const voiceX1 = padLeft + (5 / 9) * plotW - 14;
  const voiceX2 = padLeft + (7 / 9) * plotW + 14;

  const handlePointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const relX = ((e.clientX - rect.left) / rect.width) * width;
    if (relX < padLeft || relX > padLeft + plotW) {
      setHoverData(null);
      return;
    }
    const frac = (relX - padLeft) / plotW;
    const nearestIdx = Math.min(9, Math.max(0, Math.round(frac * 9)));
    const freqLabels = ["31.5 Hz", "63 Hz", "125 Hz", "250 Hz", "500 Hz", "1.0 kHz", "2.0 kHz", "4.0 kHz", "8.0 kHz", "16.0 kHz"];
    const db = bandsDb[nearestIdx];
    const clampedDb = Math.max(-10, Math.min(10, db));
    const targetY = padTop + ((10 - clampedDb) / 20) * plotH;

    setHoverData({
      x: padLeft + (nearestIdx / 9) * plotW,
      y: targetY,
      freqLabel: freqLabels[nearestIdx],
      db,
      role: ISO_BAND_DESCRIPTIONS[nearestIdx],
    });
  };

  return (
    <div className="bg-[#141923] border border-[#1E2638] rounded-3xl p-5 shadow-xl relative overflow-hidden">
      {/* Top Visualizer HUD Bar: Status, Vocal Clarity Indicator & View Mode Pill */}
      <div className="flex items-center justify-between mb-3 px-1 text-xs select-none">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-[#7BA7FF] animate-pulse" />
            <span className="font-semibold tracking-wider text-[#F1F5F9] uppercase text-[11px]">
              Dolby Atmos DSP
            </span>
            <span className="text-[#94A3B8] font-mono text-[10px]">48 kHz • 24-bit</span>
          </div>

          {/* Voice Enhancement indicator badge */}
          <div className="hidden sm:flex items-center gap-1 px-2.5 py-0.5 rounded-full bg-[#7BA7FF]/15 border border-[#7BA7FF]/35 text-[#7BA7FF] font-medium text-[11px]">
            <Sparkles className="w-3 h-3 shrink-0" />
            <span>Voice Enhancement Active (+1.8 dB)</span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Mini Stereo VU Peak Meter */}
          <div className="hidden md:flex items-center gap-1 px-2.5 py-1 rounded-lg bg-[#0B0E14] border border-[#1E2638]">
            <span className="text-[10px] text-[#94A3B8] mr-1">VU</span>
            {[...Array(6)].map((_, i) => {
              const active = (liveRms > 0.005 ? liveRms * 12 : 1) > i;
              return (
                <div
                  key={i}
                  className={`w-1 h-3 rounded-xs transition-colors duration-75 ${
                    active
                      ? i >= 4
                        ? "bg-[#F87171]"
                        : i >= 3
                        ? "bg-[#FBBF24]"
                        : "bg-[#7BA7FF]"
                      : "bg-[#1E2638]"
                  }`}
                />
              );
            })}
          </div>

          {/* Mode Pill Toggle */}
          <div className="flex items-center bg-[#0B0E14] border border-[#1E2638] rounded-full p-0.5">
            <button
              onClick={() => setVisMode("WAVE")}
              className={`flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-medium transition-colors ${
                visMode === "WAVE"
                  ? "bg-[#7BA7FF] text-[#0B0E14] shadow-sm font-semibold"
                  : "text-[#94A3B8] hover:text-[#F1F5F9]"
              }`}
              title="Dynamic Audio Waves"
            >
              <Waves className="w-3 h-3" />
              <span>Wave</span>
            </button>
            <button
              onClick={() => setVisMode("RTA")}
              className={`flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-medium transition-colors ${
                visMode === "RTA"
                  ? "bg-[#7BA7FF] text-[#0B0E14] shadow-sm font-semibold"
                  : "text-[#94A3B8] hover:text-[#F1F5F9]"
              }`}
              title="Real-Time 24-Band Spectrum Analyzer"
            >
              <BarChart3 className="w-3 h-3" />
              <span>RTA</span>
            </button>
            <button
              onClick={() => setVisMode("CURVE")}
              className={`flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-medium transition-colors ${
                visMode === "CURVE"
                  ? "bg-[#7BA7FF] text-[#0B0E14] shadow-sm font-semibold"
                  : "text-[#94A3B8] hover:text-[#F1F5F9]"
              }`}
              title="Studio Mastering Equalizer Curve"
            >
              <Activity className="w-3 h-3" />
              <span>Curve</span>
            </button>
          </div>
        </div>
      </div>

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full h-52 overflow-visible cursor-crosshair select-none"
        onPointerMove={handlePointerMove}
        onPointerLeave={() => setHoverData(null)}
      >
        <defs>
          <linearGradient id="dolbyCurveFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#7BA7FF" stopOpacity="0.38" />
            <stop offset="70%" stopColor="#7BA7FF" stopOpacity="0.08" />
            <stop offset="100%" stopColor="#7BA7FF" stopOpacity="0.0" />
          </linearGradient>

          <linearGradient id="dolbyWaveFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#38BDF8" stopOpacity="0.28" />
            <stop offset="100%" stopColor="#38BDF8" stopOpacity="0.0" />
          </linearGradient>

          <linearGradient id="dolbyWaveFill2" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#818CF8" stopOpacity="0.22" />
            <stop offset="100%" stopColor="#818CF8" stopOpacity="0.0" />
          </linearGradient>

          <linearGradient id="dolbyRtaBar" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#7BA7FF" stopOpacity="0.85" />
            <stop offset="100%" stopColor="#38BDF8" stopOpacity="0.15" />
          </linearGradient>

          <linearGradient id="voiceRegionGlow" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#7BA7FF" stopOpacity="0.12" />
            <stop offset="100%" stopColor="#7BA7FF" stopOpacity="0.02" />
          </linearGradient>
        </defs>

        {/* Y-Axis dB Grid Text (+10 db, 0 db, -10 db) */}
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

        {/* Horizontal 0 dB Reference Guideline */}
        <line
          x1={padLeft}
          y1={padTop + plotH / 2}
          x2={padLeft + plotW}
          y2={padTop + plotH / 2}
          stroke="#1E2638"
          strokeWidth="1"
          strokeDasharray="4 4"
        />

        {/* Voice Formant Intelligibility Region Highlight (1 kHz - 4 kHz) */}
        <rect
          x={voiceX1}
          y={padTop}
          width={voiceX2 - voiceX1}
          height={plotH}
          fill="url(#voiceRegionGlow)"
          rx="6"
        />
        <text
          x={(voiceX1 + voiceX2) / 2}
          y={padTop + 14}
          textAnchor="middle"
          fill="#7BA7FF"
          fontSize="9"
          fontWeight="600"
          letterSpacing="0.06em"
          opacity="0.85"
        >
          SPEECH CLARITY BAND
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

        {/* Mode: RTA Spectrum Analyzer Bars */}
        {(visMode === "RTA" || visMode === "WAVE") &&
          smoothAmpsRef.current.map((amp, idx) => {
            const barW = (plotW / smoothAmpsRef.current.length) * 0.65;
            const barX =
              padLeft +
              (idx / smoothAmpsRef.current.length) * plotW +
              barW * 0.25;
            const barHeight = Math.max(3, amp * (plotH * 0.75));
            const barY = padTop + plotH - barHeight;

            return (
              <g key={idx}>
                <rect
                  x={barX}
                  y={barY}
                  width={barW}
                  height={barHeight}
                  fill="url(#dolbyRtaBar)"
                  rx="1.5"
                  opacity={visMode === "RTA" ? 0.9 : 0.35}
                />
                {/* Peak hold cap */}
                <rect
                  x={barX}
                  y={barY - 2}
                  width={barW}
                  height={1.5}
                  fill="#7BA7FF"
                  rx="0.75"
                  opacity={0.8}
                />
              </g>
            );
          })}

        {/* Flowing Harmonic Acoustic Waves */}
        {visMode === "WAVE" && (
          <>
            <path d={waveFillD2} fill="url(#dolbyWaveFill2)" />
            <path d={waveFillD} fill="url(#dolbyWaveFill)" />
          </>
        )}

        {/* Gradient Fill Under Main Cubic Bézier Curve */}
        <path d={fillD} fill="url(#dolbyCurveFill)" />

        {/* Smooth Cubic Bézier Spline with subtle glowing drop shadow */}
        <path
          d={pathD}
          fill="none"
          stroke="#7BA7FF"
          strokeWidth="3.2"
          strokeLinecap="round"
          filter="drop-shadow(0 0 6px rgba(123, 167, 255, 0.45))"
        />

        {/* Active Points along the curve */}
        {points.map((p, idx) => (
          <circle
            key={idx}
            cx={p.x}
            cy={p.y}
            r={idx >= 5 && idx <= 6 ? "4" : "3"}
            fill={idx >= 5 && idx <= 6 ? "#FFFFFF" : "#7BA7FF"}
            stroke="#141923"
            strokeWidth="1.5"
            className="transition-all duration-150"
          />
        ))}

        {/* Interactive Hover HUD Guide & Tooltip */}
        {hoverData && (
          <g>
            <line
              x1={hoverData.x}
              y1={padTop}
              x2={hoverData.x}
              y2={padTop + plotH}
              stroke="#38BDF8"
              strokeWidth="1.5"
              strokeDasharray="3 3"
              opacity="0.9"
            />
            <circle
              cx={hoverData.x}
              cy={hoverData.y}
              r="6"
              fill="#FFFFFF"
              stroke="#38BDF8"
              strokeWidth="2.5"
            />

            {/* Floating Tooltip Box */}
            <g transform={`translate(${Math.min(width - 150, Math.max(padLeft, hoverData.x - 70))}, ${Math.max(padTop + 6, hoverData.y - 48)})`}>
              <rect
                x="0"
                y="0"
                width="140"
                height="40"
                rx="8"
                fill="#0B0E14"
                stroke="#7BA7FF"
                strokeWidth="1"
                filter="drop-shadow(0 4px 10px rgba(0,0,0,0.6))"
              />
              <text x="10" y="16" fill="#F1F5F9" fontSize="11" fontWeight="600" fontFamily="sans-serif">
                {hoverData.freqLabel}:{" "}
                <tspan fill="#7BA7FF">{hoverData.db > 0 ? `+${hoverData.db.toFixed(1)}` : hoverData.db.toFixed(1)} dB</tspan>
              </text>
              <text x="10" y="30" fill="#94A3B8" fontSize="9" fontFamily="sans-serif">
                {hoverData.role}
              </text>
            </g>
          </g>
        )}

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
