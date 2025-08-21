import Foundation
import AVFoundation
import OSLog
import WebRTC

/// A protocol to delegate audio manager events to a higher-level controller.
protocol AudioManagerDelegate: AnyObject {
    func audioManager(_ manager: AudioManager, didChangeRoute routeInfo: AudioRoutesInfo)
    func audioManager(_ manager: AudioManager, didChangeDevices routeInfo: AudioRoutesInfo)
}

/// Manages all `AVAudioSession` and WebRTC `RTCAudioSession` interactions.
/// This class operates as a state machine, commanded by the `CallEngine`.
/// It ensures that audio is only configured during an active call and is
/// completely passive otherwise, preventing interference with other app audio.
class AudioManager {
    private let logger = Logger(
        subsystem: "com.qusaieilouti99.callmanager",
        category: "AudioManager"
    )
    private weak var delegate: AudioManagerDelegate?
    private let session = AVAudioSession.sharedInstance()
    private var observers: [NSObjectProtocol] = []

    /// The current state of the audio manager. This now represents the desired output,
    /// from which the correct session `mode` is derived.
    private var state: State = .inactive

    private enum State {
        case inactive
        /// The `onSpeaker` property is now the source of truth for the audio route.
        case active(onSpeaker: Bool)
    }

    // A serial queue to ensure all audio session operations are thread-safe.
    private let audioQueue = DispatchQueue(label: "com.callmanager.audio", qos: .userInitiated)

    init(delegate: AudioManagerDelegate) {
        self.delegate = delegate
        logger.info("AudioManager initializing.")
        // This is a critical one-time setup. It tells WebRTC that we will be
        // managing the audio session's activation and configuration manually,
        // which is required for a proper CallKit integration.
        RTCAudioSession.sharedInstance().useManualAudio = true
        logger.info("WebRTC audio session set to manual control.")
    }

    deinit {
        logger.info("AudioManager deinitializing.")
        audioQueue.sync {
            self.removeAudioSessionObservers()
        }
    }

    // MARK: - Public Control Methods (Commanded by CallEngine)

    /// Activates the audio session, setting the initial route based on call type.
    func activate(isVideo: Bool) {
        audioQueue.async {
            self.logger.info("COMMAND: Activate audio session, isVideo: \(isVideo)")
            guard case .inactive = self.state else {
                self.logger.warning("Cannot activate, session already active. Ignoring.")
                return
            }
            // A video call defaults to speaker, an audio call defaults to earpiece.
            self.state = .active(onSpeaker: isVideo)
            self.configureAudioSession()
        }
    }

    /// Deactivates the audio session when the last call has ended.
    func deactivate() {
        audioQueue.async {
            self.logger.info("COMMAND: Deactivate audio session.")
            guard case .active = self.state else {
                self.logger.warning("Cannot deactivate, session already inactive. Ignoring.")
                return
            }
            self.state = .inactive
            self.configureAudioSession()
        }
    }

    /// Sets the audio route from JS. This updates the state and triggers a full reconfiguration
    /// to ensure the `AVAudioSession.Mode` and output override are perfectly in sync.
    func setAudioRoute(_ route: String) {
        audioQueue.async {
            self.logger.info("COMMAND: Set audio route to '\(route)'.")
            guard case .active = self.state else {
                self.logger.warning("Cannot set audio route, session not active. Ignoring.")
                return
            }
            let shouldBeOnSpeaker = (route == "Speaker")
            self.state = .active(onSpeaker: shouldBeOnSpeaker)
            self.configureAudioSession()
        }
    }

    // MARK: - CallKit Integration Points

    /// This is called by the system via CallKit. We treat it as a signal to
    /// ensure our configuration is applied, in case our proactive call failed.
    func callKitDidActivateAudioSession() {
        audioQueue.async {
            self.logger.info("System reported audio session activation. Verifying configuration...")
            self.configureAudioSession()
        }
    }

    // MARK: - Core Audio Session Logic

    /// The single worker function that applies the correct audio state. It is idempotent and safe to call multiple times.
    private func configureAudioSession() {
        let rtcAudioSession = RTCAudioSession.sharedInstance()
        rtcAudioSession.lockForConfiguration()
        defer {
            rtcAudioSession.unlockForConfiguration()
        }

        switch state {
        case .active(let onSpeaker):
            logger.info("Configuring for ACTIVE state (onSpeaker: \(onSpeaker)).")
            addAudioSessionObservers()
            rtcAudioSession.audioSessionDidActivate(self.session)

            let config = RTCAudioSessionConfiguration.webRTC()
            config.category = AVAudioSession.Category.playAndRecord.rawValue
            config.categoryOptions = .allowBluetooth
            // The mode is now DERIVED from the desired output, ensuring consistency for CallKit.
            config.mode =
                onSpeaker
                ? AVAudioSession.Mode.videoChat.rawValue
                : AVAudioSession.Mode.voiceChat.rawValue

            do {
                try rtcAudioSession.setConfiguration(config, active: true)
                logger.info("✅ Successfully applied WebRTC configuration with mode: \(config.mode).")
            } catch {
                logger.error("❌ Failed to set WebRTC configuration: \(error.localizedDescription)")
                return
            }

            // The override is also DERIVED from the desired output.
            do {
                let overridePort: AVAudioSession.PortOverride = onSpeaker ? .speaker : .none
                try session.overrideOutputAudioPort(overridePort)
                logger.info("✅ Set audio output override to: \(onSpeaker ? "Speaker" : "None").")
            } catch {
                logger.error("❌ Failed to set audio output override: \(error.localizedDescription)")
            }

            rtcAudioSession.isAudioEnabled = true
            notifyDelegateOfRouteChange()

        case .inactive:
            logger.info("Configuring for INACTIVE state.")
            removeAudioSessionObservers()
            rtcAudioSession.isAudioEnabled = false
            rtcAudioSession.audioSessionDidDeactivate(self.session)
            do {
                try session.setCategory(.ambient, mode: .default, options: [])
                logger.info("✅ Audio session category reset to '.ambient'.")
            } catch {
                logger.warning("Could not reset audio session category: \(error.localizedDescription)")
            }
            notifyDelegateOfRouteChange()
        }
    }

    // MARK: - Observers & State Synchronization

    private func addAudioSessionObservers() {
        guard observers.isEmpty else { return }
        logger.info("Adding audio session observers.")
        let nc = NotificationCenter.default
        observers.append(
            nc.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.audioQueue.async {
                    self?.handleRouteChange()
                }
            })
    }

    private func removeAudioSessionObservers() {
        guard !observers.isEmpty else { return }
        logger.info("Removing audio session observers.")
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
    }

    /// Handles a route change from the system (e.g., user taps CallKit button).
    /// It syncs the internal state and re-applies the configuration to ensure consistency.
    private func handleRouteChange() {
        guard case .active(let currentlyOnSpeaker) = state else { return }

        let systemRoute = determineCurrentRoute()
        let systemIsOnSpeaker = (systemRoute == "Speaker")

        // If the system's state differs from our app's state, the user has made a choice.
        // We must update our state to match and re-configure to lock it in.
        if systemIsOnSpeaker != currentlyOnSpeaker {
            logger
                .info(
                    "System route (\(systemRoute)) differs from internal state (onSpeaker: \(currentlyOnSpeaker)). Resyncing."
                )
            self.state = .active(onSpeaker: systemIsOnSpeaker)
            self.configureAudioSession()
        } else {
            // If they are already in sync, just notify the delegate of the change.
            self.notifyDelegateOfRouteChange()
        }
    }

    private func notifyDelegateOfRouteChange() {
        let info = getAudioDevices()
        logger.debug("Notifying delegate of route change: currentRoute=\(info.currentRoute)")
        DispatchQueue.main.async {
            self.delegate?.audioManager(self, didChangeRoute: info)
            self.delegate?.audioManager(self, didChangeDevices: info)
        }
    }

    // MARK: - Helpers

    func getAudioDevices() -> AudioRoutesInfo {
        var devices: Set<String> = ["Earpiece", "Speaker"]
        if let inputs = session.availableInputs, !inputs.isEmpty {
            for input in inputs {
                if [.bluetoothHFP, .bluetoothA2DP, .bluetoothLE].contains(input.portType) {
                    devices.insert("Bluetooth")
                } else if [.headphones, .headsetMic].contains(input.portType) {
                    devices.insert("Headset")
                }
            }
        }
        let route = determineCurrentRoute()
        let deviceHolders = Array(devices).sorted().map { StringHolder(value: $0) }
        return AudioRoutesInfo(devices: deviceHolders, currentRoute: route)
    }

    private func determineCurrentRoute() -> String {
        guard let output = session.currentRoute.outputs.first else {
            return "Earpiece"
        }
        switch output.portType {
        case
