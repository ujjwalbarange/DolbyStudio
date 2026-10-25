export type AudioModeId =
  | "SPATIAL_AUDIO"
  | "SMART_AUDIO"
  | "MUSIC"
  | "FILM"
  | "GAME"
  | "PODCAST"
  | "CUSTOM";

export type EqualiserPresetId =
  | "BRILLIANT_TREBLE"
  | "BASS_BOOST"
  | "VOCAL_BOOST"
  | "FLAT"
  | "MANUAL";

export interface WasapiEndpoint {
  id: string;
  name: string;
  description: string;
  isDefault: boolean;
  isHeadphone: boolean;
  isSelected: boolean;
}

export interface ProfileSettings {
  mode: AudioModeId;
  preset: EqualiserPresetId;
  manualBandsDb: number[]; // 10 bands: 31.5Hz to 16kHz (-10 to +10 dB)
  macroBassTiltDb: number;
  macroTrebleTiltDb: number;
  surroundVirtualiser: boolean;
  volumeLeveller: boolean;
}

export const ISO_10_BAND_LABELS = [
  "31.5",
  "63",
  "125",
  "250",
  "500",
  "1k",
  "2k",
  "4k",
  "8k",
  "16k",
];

export const PRESET_CURVES: Record<EqualiserPresetId, number[]> = {
  BRILLIANT_TREBLE: [0.0, 0.0, 0.3, 0.8, 1.8, 3.2, 4.8, 6.2, 7.4, 8.0],
  BASS_BOOST: [7.5, 6.8, 5.2, 3.0, 1.0, 0.0, 0.0, 0.5, 1.0, 1.2],
  VOCAL_BOOST: [-1.5, -1.0, 0.0, 2.2, 4.8, 6.5, 5.8, 3.2, 0.5, -0.5],
  FLAT: [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
  MANUAL: [-3.2, -3.5, 2.8, 2.0, 2.1, -3.5, 0.0, 0.0, -4.8, 6.5],
};

export interface AudioModeMeta {
  id: AudioModeId;
  title: string;
  subtitle: string;
  section: "SPATIAL" | "STEREO";
  hasEditor: boolean;
}

export const AUDIO_MODES: AudioModeMeta[] = [
  {
    id: "SPATIAL_AUDIO",
    title: "Spatial audio",
    subtitle: "Make sound seem like it comes from all around you",
    section: "SPATIAL",
    hasEditor: false,
  },
  {
    id: "SMART_AUDIO",
    title: "Smart audio",
    subtitle: "Automatically identify what plays and make adjustments for the best sound",
    section: "STEREO",
    hasEditor: false,
  },
  {
    id: "MUSIC",
    title: "Music",
    subtitle: "Deliver rich, balanced sound for all your music",
    section: "STEREO",
    hasEditor: true,
  },
  {
    id: "FILM",
    title: "Film",
    subtitle: "Deliver surround sound and clear dialogue for films",
    section: "STEREO",
    hasEditor: true,
  },
  {
    id: "GAME",
    title: "Game",
    subtitle: "Give sound effects impact and spatial accuracy",
    section: "STEREO",
    hasEditor: true,
  },
  {
    id: "PODCAST",
    title: "Podcast",
    subtitle: "Keep voices clear and volume consistent",
    section: "STEREO",
    hasEditor: true,
  },
  {
    id: "CUSTOM",
    title: "Custom",
    subtitle: "Tune the equaliser for your preferences",
    section: "STEREO",
    hasEditor: true,
  },
];
