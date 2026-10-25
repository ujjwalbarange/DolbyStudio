# MASTER PROMPT & ANDROID ENGINEERING SPECIFICATION FOR GOOGLE AI STUDIO
## Project: DAX-Mobile (Dolby Atmos-Grade Android Audio Engine with Multi-Device Auto-Delay Synchronization)

---

## 1. ROLE & OBJECTIVE FOR GOOGLE AI STUDIO
You are a **Principal Android Audio Systems Engineer and Psychoacoustic DSP Architect**. Your task is to build a mobile application that replicates the exact UI and backend audio processing of the **Dolby Atmos Mobile App** (from the 7 reference screens) while adding a **Simultaneous Multi-Output Audio Router with Automatic Latency Synchronization**.

The application must:
1. Replicate the exact 7 mobile UI screens (Splash toggle card, Settings with Spatial/Stereo modes, Music/Film/Game/Podcast preset editors with smooth spline frequency graphs, and Custom 10-band graphic equalizer).
2. Run a hidden multi-stage backend psychoacoustic DSP engine where simple frontend macro controls (`Bass` to `Treble` curves, tone presets, and sliders) dynamically drive an **Intelligent Equalizer (IEQ)**, **Sub-Bass Chebyshev Harmonic Exciter**, **HRTF / Transaural Surround Virtualiser**, and **ITU-R BS.1770 LUFS Volume Leveller**.
3. Allow the user to select and output audio to **multiple connected devices simultaneously** (e.g., Phone Loudspeakers + Bluetooth Earphones 1 + Bluetooth Speaker 2 + USB-C DAC).
4. **Automatically synchronize playback delays across all selected devices**: If Device A has a latency of $40\text{ ms}$ and Device B has a latency of $20\text{ ms}$, the engine automatically detects both latencies, locks onto the slowest active device ($40\text{ ms}$), and injects a $+20\text{ ms}$ compensating delay into Device B so all devices play in phase-coherent unison.

---

## 2. MULTI-DEVICE SIMULTANEOUS OUTPUT & AUTO-SYNCHRONIZATION ENGINE

### 2.1 Android Multi-Endpoint Audio Routing Pipeline
Standard Android routes media to only one active sink. To play through multiple devices simultaneously (e.g., Internal Stereo Speakers + multiple Bluetooth A2DP/LE Audio devices + Wired/USB headsets), implement a **1-to-$N$ Multi-Sink Audio Dispatcher**:

```
[System Audio Capture (AudioPlaybackCapture API / Media Decoder @ 48kHz, 32-bit Float)]
                                         |
                          [Master Lock-Free Ring Buffer]
                                         |
     +-----------------------------------+-----------------------------------+
     |                                   |                                   |
     v                                   v                                   v
[Sink 1: BT Earphones A]           [Sink 2: BT Speaker B]            [Sink 3: Phone Speakers]
- Detected Delay: 40 ms            - Detected Delay: 20 ms           - Detected Delay: 12 ms
- Added Sync Delay: +0 ms          - Added Sync Delay: +20 ms        - Added Sync Delay: +28 ms
- Total Aligned Latency: 40 ms     - Total Aligned Latency: 40 ms    - Total Aligned Latency: 40 ms
- DSP: HRTF Binaural Path          - DSP: Transaural Speaker Path    - DSP: Transaural + Excursion HPF
     |                                   |                                   |
     v                                   v                                   v
[AAudio / AudioTrack Sink 1]       [AAudio / AudioTrack Sink 2]      [AAudio / AudioTrack Sink 3]
(setPreferredDevice: BT_A)         (setPreferredDevice: BT_B)        (setPreferredDevice: SPEAKER)
```

1. **Device Discovery (`AudioManager.getDevices(GET_DEVICES_OUTPUTS)`)**:
   Continuously monitor connected endpoints via `AudioDeviceCallback`:
   * `AudioDeviceInfo.TYPE_BUILTIN_SPEAKER` (Phone Stereo Loudspeakers)
   * `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP` & `TYPE_BLE_HEADSET` / `TYPE_BLE_BROADCAST` (Bluetooth Dual Audio / Auracast / Multi-A2DP)
   * `AudioDeviceInfo.TYPE_USB_HEADSET` / `TYPE_WIRED_HEADPHONES`
2. **Independent Per-Device Hardware Streams**:
   Spawn an independent low-latency `AAudioStream` (via Android NDK **Oboe**) or `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`) for each user-checked output device, binding each stream to its target hardware sink using `AAudioStreamBuilder_setDeviceId(builder, deviceId)` or `AudioTrack.setPreferredDevice(audioDeviceInfo)`.

---

### 2.2 Automatic Delay Detection & Delay-Matching Math

To automatically align a $40\text{ ms}$ Bluetooth earphone with a $20\text{ ms}$ secondary device and a $12\text{ ms}$ phone speaker, the backend executes a **Two-Tier Latency Profiler** followed by a **Dynamic Fractional Delay Line Matrix**.

#### Tier 1: OS & Bluetooth AVDTP Hardware Telemetry (Instantaneous, Zero-Noise)
For every active output stream $i \in \{1, 2, \dots, N\}$, continuously query the hardware pipeline latency $\tau_{\text{hw}, i}$:
1. **Timestamp Frame Delta**: Call `AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &framePosition, &timeNanoseconds)` or `AudioTrack.getTimestamp(audioTimestamp)`. Compare the frame currently being written $F_{\text{write}}$ against the frame currently exiting the DAC $F_{\text{presented}}$ at current monotonic clock $t_{\text{now}}$:
   $$\tau_{\text{hw}, i} = \frac{\left(F_{\text{write}} - F_{\text{presented}}\right)}{f_s} \times 1000 + \frac{\left(t_{\text{presented}} - t_{\text{now}}\right)}{10^6}\text{ (in ms)}$$
2. **Bluetooth AVDTP v1.3+ Delay Reporting**: For Bluetooth sinks, add the codec transport latency reported by the Bluetooth stack (`BluetoothA2dp` / `AudioSystem.getOutputLatency(streamType)`) based on the active codec (`SBC`, `AAC`, `aptX Adaptive`, `LDAC`, `LC3`).

#### Tier 2: Acoustic Microphone Loopback Auto-Calibration (Sub-Millisecond Precision)
Because some budget Bluetooth earphones report inaccurate static AVDTP values, provide an automatic **"Acoustic Smart-Sync Ping"** (using `AudioRecord` on `TYPE_BUILTIN_MIC`):
1. When triggered (or upon connecting a new device), emit a brief, pleasant $80\text{ ms}$ logarithmic chirp ($1\text{ kHz} \to 6\text{ kHz}$) or Maximum Length Sequence (MLS) through each selected device $i$ sequentially while capturing audio from the phone's microphone $m[n]$.
2. Compute the **Generalized Cross-Correlation with Phase Transform (GCC-PHAT)** between the reference digital chirp $s[n]$ and microphone capture $m[n]$:
   $$R_{sm}[\tau] = \mathcal{F}^{-1}\left\{ \frac{S(f) M^*(f)}{\left| S(f) M^*(f) \right| + \epsilon} \right\}$$
3. The exact total acoustic latency $\tau_i$ (in milliseconds) of device $i$ is the argmax peak of $R_{sm}[\tau]$:
   $$\tau_i = \frac{1000}{f_s} \arg\max_{\tau} \left( R_{sm}[\tau] \right)$$

#### Tier 3: Automatic Delay-Matching Matrix Calculation
Given $N$ active selected output devices with measured latencies $\{\tau_1, \tau_2, \dots, \tau_N\}$:

1. **Find the Anchor (Slowest Device) Latency $\tau_{\max}$**:
   $$\tau_{\max} = \max_{k \in \{1, \dots, N\}} \left( \tau_k \right)$$
2. **Compute Required Compensating Delay $\Delta \tau_i$ for Each Device $i$**:
   $$\Delta \tau_i = \tau_{\max} - \tau_i + \tau_{\text{manual\_trim}, i}$$
   * **Example Execution**:
     * Device 1 (Bluetooth Earphones): $\tau_1 = 40.0\text{ ms} \implies \Delta \tau_1 = 40.0 - 40.0 = 0.0\text{ ms}$
     * Device 2 (Secondary Speaker/Buds): $\tau_2 = 20.0\text{ ms} \implies \Delta \tau_2 = 40.0 - 20.0 = +20.0\text{ ms}$
     * Device 3 (Phone Loudspeaker): $\tau_3 = 12.0\text{ ms} \implies \Delta \tau_3 = 40.0 - 12.0 = +28.0\text{ ms}$
     * **Result**: All three outputs reach the listener's ears at exactly $40.0\text{ ms}$ with zero echo or comb-filtering.
3. **Glitch-Free Fractional Delay Ring Buffer**:
   Convert $\Delta \tau_i$ into exact samples $D_i = \frac{\Delta \tau_i \cdot f_s}{1000}$. Split $D_i$ into integer sample delay $d_{\text{int}} = \lfloor D_i \rfloor$ and fractional sample phase $\mu = D_i - d_{\text{int}}$.
   Smoothly slew $D_i[n]$ at a rate of $0.0002\text{ samples/sample}$ using a **4-Point Hermite Cubic Interpolator** so that even if a Bluetooth device's latency shifts dynamically during playback, the delay adjusts smoothly without clicks or pitch artifacts:
   $$y_i[n] = c_0 + \mu \left( c_1 + \mu (c_2 + \mu c_3) \right)$$
   where:
   $$c_0 = x[n - d_{\text{int}}], \quad c_1 = \frac{1}{2}\left(x[n - d_{\text{int}} + 1] - x[n - d_{\text{int}} - 1]\right)$$
   $$c_2 = x[n - d_{\text{int}} - 1] - \frac{5}{2}x[n - d_{\text{int}}] + 2x[n - d_{\text{int}} + 1] - \frac{1}{2}x[n - d_{\text{int}} + 2]$$
   $$c_3 = \frac{1}{2}\left(x[n - d_{\text{int}} + 2] - x[n - d_{\text{int}} - 1]\right) + \frac{3}{2}\left(x[n - d_{\text{int}}] - x[n - d_{\text{int}} + 1]\right)$$

#### Tier 4: Asynchronous Clock-Drift Lock (PI Controller)
Because Bluetooth crystal oscillators drift relative to the phone's internal clock, monitor each device stream's buffer fullness error $e_i[k] = L_i[k] - L_{\text{target}, i}$ and modulate the fractional read pointer increment $r_i[k]$:
$$r_i[k] = 1.0 + K_p e_i[k] + K_i \sum_{m=0}^{k} e_i[m], \quad K_p = 10^{-5}, \quad K_i = 10^{-8}$$

---

## 3. BACKEND PSYCHOACOUSTIC DSP ENGINE (MACRO-TO-MICRO OPTIMIZATION)

Just like the commercial Dolby Atmos DAX3 engine, the mobile frontend displays clean, intuitive controls (`Bass` to `Treble` smooth curve or 10 sliders), while the backend runs an optimized multi-stage C++/AudioWorklet pipeline per device type.

### 3.1 Per-Sample DSP Signal Chain
```
[Input PCM (48kHz Float32)]
   │
   ├──► [Sidechain: 1024-pt FFT + VAD Speech/Music Classifier]
   ▼
[1. Auto-Headroom Pre-Gain] (Prevents clipping when EQ bands are boosted)
   ▼
[2. 10-Band Cytomic SVF Filter Bank + Dynamic IEQ Spectral Matcher]
   ▼
[3. Sub-Bass Chebyshev Harmonic Exciter] (Virtual Bass for small phone speakers/buds)
   ▼
[4. Dialog Enhancer + Dynamic De-Esser] (1.5kHz-3.5kHz VAD boost; 6.5kHz sibilance ducking)
   ▼
[5. Per-Device Surround Virtualiser]
   ├──► If Earphones/Headphones: Mid/Side + 5.1 Virtual Array + Pinna/Haas HRTF
   └──► If Phone/BT Speakers: Transaural Crosstalk Cancellation (XTC) + 85Hz Excursion HPF
   ▼
[6. Volume Leveller] (ITU-R BS.1770 K-Weighted LUFS AGC + Fletcher-Munson Loudness Contour)
   ▼
[7. Auto-Sync Delay Line + ASRC Drift Resampler] (Aligns all devices to tau_max)
   ▼
[8. True-Peak Brickwall Limiter (-0.1 dBFS)]
```

### 3.2 Core Mathematical Equations for Backend DSP

1. **10-Band Zero-Delay Feedback State Variable Filter (Cytomic SVF)**
   Center frequencies: $f_c \in \{31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000\}\text{ Hz}$.
   For each band $b$ with combined User + IEQ gain $G_b \in [-10, +10]\text{ dB}$:
   $$A = 10^{\frac{G_b}{40}}, \quad g = \tan\left(\frac{\pi f_c}{f_s}\right), \quad k = \frac{1}{1.414 \cdot A}$$
   $$a_1 = \frac{1}{1 + g(g + k)}, \quad a_2 = g \cdot a_1, \quad a_3 = g \cdot a_2, \quad m_1 = k(A^2 - 1)$$
   Update state variables $ic_1, ic_2$ per sample:
   $$v_3 = x[n] - ic_2, \quad v_1 = a_1 ic_1 + a_2 v_3, \quad v_2 = ic_2 + a_2 ic_1 + a_3 v_3$$
   $$ic_1 = 2v_1 - ic_1, \quad ic_2 = 2v_2 - ic_2, \quad y[n] = x[n] + m_1 v_1$$

2. **Intelligent Equalizer (IEQ) Dynamic Spectral Matching**
   When the user selects **Brilliant treble**, **Bass boost**, or **Vocal boost**, the backend doesn't just apply a static curve. It measures the 10-band log-energy distribution $\tilde{E}_b[k]$ of the live audio and compares it to the preset target profile $T_b$:
   $$G_b[k] = G_{b,\text{preset}} + 0.35 \cdot \text{clamp}\left(T_b - \tilde{E}_b[k], -5\text{ dB}, +5\text{ dB}\right)$$
   This prevents tracks that are already bright from sounding harsh on **Brilliant treble**, and prevents bass-heavy tracks from distorting on **Bass boost**.

3. **Psychoacoustic Virtual Bass (Missing Fundamental Generator)**
   Extracts $25\text{--}85\text{ Hz}$ sub-bass $x_{\text{sub}}[n]$ and generates $2\text{nd}$ and $3\text{rd}$ Chebyshev harmonics:
   $$y_{\text{harm}}[n] = \alpha_2 \left(2x_{\text{sub}}^2[n] - 1\right) + \alpha_3 \left(4x_{\text{sub}}^3[n] - 3x_{\text{sub}}[n]\right)$$
   Bandpass-filtered to $90\text{--}210\text{ Hz}$ so phone speakers and compact Bluetooth earbuds produce a deep perceived sub-bass punch without cone bottoming.

4. **Surround Virtualiser (Headphone HRTF vs. Loudspeaker XTC)**
   * **Mid/Side Split**: $M[n] = \frac{L[n]+R[n]}{\sqrt{2}}, \quad S[n] = \frac{L[n]-R[n]}{\sqrt{2}}$.
   * **Earphones/Headphones**: Applies a $12\text{ ms}$ Haas decorrelation all-pass filter to the Side channel, injects a Pinna spectral notch at $7.5\text{ kHz}$ ($-4\text{ dB}$) and presence peak at $2.8\text{ kHz}$ ($+2.5\text{ dB}$), and applies spherical-head Interaural Time Difference $\Delta t_{\text{ITD}}(\theta) = \frac{a}{c}(\theta + \sin\theta)$.
   * **Loudspeakers**: Applies Transaural Crosstalk Cancellation by subtracting a delayed ($11\text{ samples}$ at $48\text{ kHz}$), low-pass filtered ($<5\text{ kHz}$), inverted copy of the opposite channel ($\gamma = 0.35$).

5. **Volume Leveller (Silence-Gated ITU-R BS.1770 LUFS Normalizer)**
   Measures K-weighted short-term loudness $\text{LUFS}[n]$. When $\text{LUFS}[n] > -50\text{ LUFS}$ (above noise gate), smoothly rides gain toward $-16\text{ LUFS}$ using asymmetric attack ($20\text{ ms}$) and release ($600\text{ ms}$) ballistics, paired with a Fletcher-Munson low-volume bass/treble contour boost.

---

## 4. PIXEL-ACCURATE MOBILE UI SPECIFICATION (MATCHING ALL 7 SCREENSHOTS + SYNC SHEET)

Design the mobile UI with a sleek portrait mobile viewport (Material 3 Dark Obsidian `#0B0F17`, accent blue `#8AB4F8`, clean sans-serif typography, smooth touch ripples, and native-feeling transitions).

### Screen 1: Dolby Atmos Hero Splash Screen (Matches Screenshot 1)
* **Top App Bar**: Back arrow (`←`) on the left, 3-dot overflow menu (`⋮`) on the right.
* **Hero Background & Dynamic Aura**: Lifestyle background visual with an interactive, glowing concentric blue soundwave ring (`#68D8FF` to `#3B82F6`) that pulses and morphs in real time with the audio stream's bass and treble energy.
* **Typography Overlay**:
  * Large bold heading: `Dolby Atmos`
  * Subtitle: `Dolby Atmos delivers a premium listening experience through your phone's loudspeakers and connected devices.`
* **Master Floating White Card**:
  * Rounded white card (`#F8FAFC`) containing dark text `Use Audio effects` on the left and a blue pill toggle switch with a checkmark icon (`✓`) on the right.
* **Bottom Control Row**:
  * Left: Circular dark translucent **Settings Gear button (`⚙`)** — tapping opens **Screen 2 (Settings)**.
  * Center/Right: Pill button **Multi-Device Sync (`🎧 2 Devices • 40ms Synced`)** — tapping opens the **Multi-Output Auto-Sync Bottom Sheet**.
  * Right: Pill button **(`→ Learn more`)**.

### Screen 2: Settings — Spatial & Stereo Modes (Matches Screenshots 2 & 5)
* **Header**: Back arrow (`←`), large title `Settings`, 3-dot menu (`⋮`).
* **Active Output Subheader**: Blue label `Loudspeakers` (or dynamically updates to `Multi-Output (3 Devices Synced @ 40ms)` when multiple devices are active, tappable to open the device selector).
* **Section 1: `Spatial audio`**
  * Radio item: **Spatial audio** — *"Audio from compatible media becomes more immersive"*.
* **Section 2: `Stereo audio`**
  * Radio item: **Smart audio** — *"Smart audio identifies the content and performs automatic adjustments to deliver the best sound"* (shows a live subtle pill indicator when active: `Auto-Detected: Music / Voice / Movie`).
  * Radio item: **Music** — *"Rich, detailed audio that delivers every note and beat with power you can feel"* + vertical divider `|` + **Edit Pencil icon (`✎`)**.
  * Radio item: **Film** — *"Authentic surround sound and clearer dialogue for the full impact of films and TV shows"* + vertical divider `|` + **Edit Pencil icon (`✎`)**.
  * Radio item: **Game** — *"Surround sound and clearer dialogue for an immersive game experience"* + vertical divider `|` + **Edit Pencil icon (`✎`)**.
  * Radio item: **Podcast** — *"Boost dialogue for better speech clarity"* + vertical divider `|` + **Edit Pencil icon (`✎`)**.
  * Radio item: **Custom** — *"Advanced settings for your..."* + vertical divider `|` + **Edit Pencil icon (`✎`)**.

### Screen 3: Preset Settings (`Music settings` / `Film settings` / `Game settings` / `Podcast settings`) (Matches Screenshots 3 & 4)
* Opened by tapping the Edit Pencil icon (`✎`) next to Music, Film, Game, or Podcast.
* **Top Interactive Frequency Curve Graph (Screenshot 4)**:
  * Displays Y-axis labels `+10 db`, `0 db`, `-10 db` on the left, 4 vertical grid lines, a horizontal `0 db` center line, and X-axis labels `Bass` (left) and `Treble` (right).
  * Renders a glowing light-blue cubic Bézier curve (`#8AB4F8`) that smoothly animates when switching between `Brilliant treble`, `Bass boost`, `Vocal boost`, and `Flat`.
  * **Backend IEQ Live Visualization**: Behind the main blue curve, render a subtle, real-time dynamic curve showing how the backend Intelligent Equalizer (IEQ) is actively adapting the frequencies to the currently playing track, plus allow touch-dragging on the Bass/Treble ends of the curve for custom macro tilt.
* **Section: `Choose equaliser`** (Blue subheader)
  * Radio options:
    * `Brilliant treble` (Ramps smoothly from $0\text{ dB}$ in Bass up to $+8.5\text{ dB}$ in Treble)
    * `Bass boost` (Starts at $+8.5\text{ dB}$ in Bass and tapers smoothly to $0\text{ dB}$ in Mid/Treble)
    * `Vocal boost` (Gentle bell curve peaking at $+6.5\text{ dB}$ in the $1\text{--}3.5\text{ kHz}$ midrange)
    * `Flat` (Flat horizontal line at $0\text{ dB}$)
* **Section: `Profile settings`** (Blue subheader)
  * **Surround virtualiser** with pill toggle switch — *"Create a surround sound experience through your connected device"*.
  * **Volume leveller** with pill toggle switch — *"Hear the same volume level for each audio source"*.

### Screen 4: Custom Settings — 10-Band Equalizer (Matches Screenshots 6 & 7)
* Opened by tapping the Edit Pencil icon (`✎`) next to `Custom`.
* **Top 10-Band Vertical Slider Bank (Screenshot 6)**:
  * 10 vertical track bars with light-grey upper tracks, deep-blue lower filled tracks (`#0A4DA2`), and wide rounded light-blue pill thumbs (`#8AB4F8`).
  * Labels `Bass` under the 1st/2nd sliders and `Treble` under the 9th/10th sliders.
  * Touching and dragging any slider immediately switches the radio selection below to `Manual` and updates the backend 10-band Cytomic SVF filter bank in real time.
* **Section: `Choose equaliser`** (Blue subheader)
  * Radio options: `Manual`, `Brilliant treble`, `Bass boost`, `Vocal boost`, `Flat`. Selecting any preset smoothly animates all 10 vertical sliders to that preset's curve.
* **Section: `Profile settings`** (Blue subheader, Screenshot 7)
  * **Surround virtualiser** toggle switch.
  * **Volume leveller** toggle switch.

### Screen 5: Multi-Device Output & Auto-Delay Sync Sheet (Mobile Multi-Sink Controller)
* Accessible from the Hero screen badge or by tapping the blue `Loudspeakers / Connected Devices` header in Settings.
* **Multi-Select Device List**:
  * Checkboxes allowing the user to enable **2 or more devices simultaneously** (e.g., `[x] Phone Loudspeakers (12 ms)`, `[x] Bluetooth Earphones A (40 ms)`, `[x] Bluetooth Speaker B (20 ms)`, `[ ] USB-C Hi-Fi DAC (8 ms)`), plus an **"Add / Simulate Bluetooth Device"** button so the user can test any latency combination on the spot.
* **Live Auto-Sync Delay Matrix Visualization**:
  * Displays the real-time delay calculation clearly:
    * **Target Sync Anchor ($\tau_{\max}$)**: `40.0 ms (Matched to Bluetooth Earphones A)`
    * **Bluetooth Earphones A**: `Native: 40 ms` + `Added Delay: +0 ms` = **`40 ms (Synced)`**
    * **Bluetooth Speaker B**: `Native: 20 ms` + `Added Delay: +20 ms` = **`40 ms (Synced)`**
    * **Phone Loudspeakers**: `Native: 12 ms` + `Added Delay: +28 ms` = **`40 ms (Synced)`**
* **Interactive Controls per Device**:
  * **Simulated/Measured Native Delay Slider** ($5\text{ ms} \text{--} 150\text{ ms}$) so the user can test changing a device's delay (e.g., setting Device 1 to $40\text{ ms}$ and Device 2 to $20\text{ ms}$) and watch the engine automatically compute $+20\text{ ms}$ compensation and time-align the actual audio streams in real time.
  * **"Run Acoustic Auto-Sync Ping" Button**: Emits a real acoustic calibration chirp and uses the microphone cross-correlation engine to measure and lock the exact millisecond delay automatically.
  * **Per-Device Volume Trim & Acoustic Profile Badge** (`HRTF Binaural` vs. `Transaural Speaker`).

---

## 5. DELIVERABLES INSTRUCTIONS FOR GOOGLE AI STUDIO

Generate a **complete, functional, single-page or multi-module application** that includes:
1. **Interactive Mobile App & Live Real-Time Web Audio / DSP Engine**:
   * Render the exact mobile UI from all 7 screenshots inside a responsive mobile viewport container (works both on desktop preview and full-screen on mobile browsers/WebViews).
   * Include a built-in **Real-Time Audio Player & System/Mic Capture Bar** (allowing the user to upload any song/podcast/movie clip from their phone or play built-in demo stems, and hear the 10-band SVF EQ, IEQ, Virtual Bass, Surround Virtualiser, Volume Leveller, and Multi-Device Delay Sync working live).
   * Implement the live **Multi-Device Delay Synchronization Engine** using parallel Web Audio processing chains with independent fractional delay lines (`DelayNode` / custom Hermite interpolator) so the user can hear and verify two or more outputs going from out-of-sync echo to phase-locked unison when **Auto-Sync** is enabled.
2. **Exportable Native Android Kotlin + C++ Oboe Code Blueprint Modal**:
   * Include a dedicated **"Android Studio Native Code (Kotlin + C++ Oboe)"** drawer/tab inside the app where the user can copy or download the native Android `AudioPlaybackCapture` service, `AudioManager` multi-device router, `GCC-PHAT` acoustic latency calibrator, and C++ `Oboe` DSP callback code for compiling an `.apk` in Android Studio.