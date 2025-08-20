import Foundation
import AVFoundation
import OSLog
import WebRTC

protocol AudioManagerDelegate: AnyObject {
    func audioManager(_ manager: AudioManager, didChangeRoute routeInfo: AudioRoutesInfo)
    func audioManager(_ manager: AudioManager, didChangeDevices routeInfo: AudioRoutesInfo)
    func audioManagerDidActivateAudioSession(_ manager: AudioManager)
    func audioManagerDidDeactivateAudioSession(_ manager: AudioManager)
}

class AudioManager {
    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "CallManager")
    private weak var delegate: AudioManagerDelegate?
    private let session = AVAudioSession.sharedInstance()
    private var observers: [NSObjectProtocol] = []
    private var isSessionActive: Bool = false

    // Audio session configuration state
    private var currentAudioConfiguration: RTCAudioSessionConfiguration?
    private var preferredAudioRoute: String?
    private var isManualAudioEnabled: Bool = true
    private var audioActivationRetryCount: Int = 0
    private let maxRetryCount: Int = 3

    // Queue for audio session operations to ensure thread safety
    private let audioQueue = DispatchQueue(label: "com.callmanager.audio", qos: .userInitiated)

    init(delegate: AudioManagerDelegate) {
        self.delegate = delegate
        logger.info("AudioManager init")
        setupNotifications()
        setupWebRTCAudio()
        setupInitialAudioSession()
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        resetAudioSessionToDefault()
    }

    // MARK: - Setup Methods

    private func setupNotifications() {
        let nc = NotificationCenter.default

        observers.append(
            nc.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil,
                queue: nil
            ) { [weak self] n in
                self?.audioQueue.async {
                    self?.handleRouteChange(n)
                }
            }
        )

        observers.append(
            nc.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: nil,
                queue: nil
            ) { [weak self] n in
                self?.audioQueue.async {
                    self?.handleInterruption(n)
                }
            }
        )

        observers.append(
            nc.addObserver(
                forName: AVAudioSession.mediaServicesWereResetNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.audioQueue.async {
                    self?.handleMediaServicesReset()
                }
            }
        )

        observers.append(
            nc.addObserver(
                forName: AVAudioSession.silenceSecondaryAudioHintNotification,
                object: nil,
                queue: nil
            ) { [weak self] n in
                self?.audioQueue.async {
                    self?.handleSilenceSecondaryAudioHint(n)
                }
            }
        )

        logger.info("AudioManager notifications set")
    }

    private func setupInitialAudioSession() {
        audioQueue.async {
            self.logger.info("Setting up initial audio session")
            do {
                // Use a safe default category that won't interfere with other apps
                try self.session.setCategory(.ambient, mode: .default, options: [])
                self.logger.info("Initial audio session configured")
            } catch {
                self.logger.error("Failed to setup initial audio session: \(error.localizedDescription)")
            }
        }
    }

    private func setupWebRTCAudio() {
        let rtcAudioSession = RTCAudioSession.sharedInstance()
        rtcAudioSession.useManualAudio = true
        rtcAudioSession.isAudioEnabled = false
        logger.info("WebRTC audio session set to manual")
    }

    // MARK: - CallKit Integration Points

    func callKitDidActivateAudioSession(_ audioSession: AVAudioSession) {
        audioQueue.async {
            self.logger.info("CallKit didActivate audioSession, configuring for VoIP")
            self.isSessionActive = true
            self.audioActivationRetryCount = 0

            // Configure WebRTC audio session properly
            let rtcAudioSession = RTCAudioSession.sharedInstance()
            rtcAudioSession.lockForConfiguration()

            do {
                // First, notify WebRTC about the activation
                rtcAudioSession.audioSessionDidActivate(audioSession)

                // Configure for VoIP with proper settings
                let config = RTCAudioSessionConfiguration.webRTC()
                config.category = AVAudioSession.Category.playAndRecord.rawValue
                config.mode = AVAudioSession.Mode.voiceChat.rawValue
                config.categoryOptions = [
                    .allowBluetooth,
                    .allowBluetoothA2DP,
                    .duckOthers
                ]

                try rtcAudioSession.setConfiguration(config)
                self.currentAudioConfiguration = config

                // Enable audio
                rtcAudioSession.isAudioEnabled = true

                self.logger.info("WebRTC audio session properly configured and activated")

            } catch {
                self.logger.error("Failed to configure RTCAudioSession: \(error.localizedDescription)")
            }

            rtcAudioSession.unlockForConfiguration()

            // Notify delegate on main queue
            DispatchQueue.main.async {
                self.delegate?.audioManagerDidActivateAudioSession(self)
            }

            // Restore preferred audio route if set
            if let preferredRoute = self.preferredAudioRoute {
                self.logger.info("Restoring preferred audio route: \(preferredRoute)")
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    self.setAudioRoute(preferredRoute, force: true)
                }
            }

            // Always notify about route change after activation
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                self.notifyRouteChange()
                self.notifyDevicesChanged()
            }
        }
    }

    func callKitDidDeactivateAudioSession(_ audioSession: AVAudioSession) {
        audioQueue.async {
            self.logger.info("CallKit didDeactivate audioSession, cleaning up")
            self.isSessionActive = false

            let rtcAudioSession = RTCAudioSession.sharedInstance()
            rtcAudioSession.lockForConfiguration()

            // Disable audio first
            rtcAudioSession.isAudioEnabled = false

            // Notify WebRTC about deactivation
            rtcAudioSession.audioSessionDidDeactivate(audioSession)

            rtcAudioSession.unlockForConfiguration()

            // Reset to default configuration
            self.resetAudioSessionToDefault()

            // Clear current configuration
            self.currentAudioConfiguration = nil

            // Notify delegate on main queue
            DispatchQueue.main.async {
                self.delegate?.audioManagerDidDeactivateAudioSession(self)
            }

            // Notify route change
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                self.notifyRouteChange()
                self.notifyDevicesChanged()
            }
        }
    }

    // MARK: - Audio Route Management

    func getAudioDevices() -> AudioRoutesInfo {
        var devices = ["Earpiece", "Speaker"]

        if let inputs = session.availableInputs {
            for input in inputs {
                switch input.portType {
                case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
                    if !devices.contains("Bluetooth") {
                        devices.append("Bluetooth")
                    }
                case .headphones, .headsetMic:
                    if !devices.contains("Headset") {
                        devices.append("Headset")
                    }
                case .carAudio:
                    if !devices.contains("CarAudio") {
                        devices.append("CarAudio")
                    }
                default:
                    break
                }
            }
        }

        let route = determineCurrentRoute()
        let deviceHolders = devices.map { StringHolder(value: $0) }
        logger.debug("getAudioDevices: route=\(route), devices=\(devices)")
        return AudioRoutesInfo(devices: deviceHolders, currentRoute: route)
    }

    func setAudioRoute(_ route: String, force: Bool = false) {
        audioQueue.async {
            self.logger.info("setAudioRoute: \(route), isSessionActive=\(self.isSessionActive), force=\(force)")

            // Store user preference
            self.preferredAudioRoute = route

            // If session is not active and not forced, queue the route change
            if !self.isSessionActive && !force {
                self.logger.info("Session not active, storing preferred route: \(route)")
                return
            }

            self.performRouteChange(route)
        }
    }

    private func performRouteChange(_ route: String) {
        do {
            let rtcAudioSession = RTCAudioSession.sharedInstance()

            rtcAudioSession.lockForConfiguration()

            defer {
                rtcAudioSession.unlockForConfiguration()
            }

            switch route {
            case "Speaker":
                try session.overrideOutputAudioPort(.speaker)
                try session.setPreferredInput(nil)
                logger.info("Audio route set to Speaker")

            case "Bluetooth":
                if let bluetoothInput = session.availableInputs?.first(where: {
                    $0.portType == .bluetoothHFP || $0.portType == .bluetoothA2DP || $0.portType == .bluetoothLE
                }) {
                    try session.setPreferredInput(bluetoothInput)
                    logger.info("Audio route set to Bluetooth: \(bluetoothInput.portName)")
                } else {
                    logger.warning("No Bluetooth input available")
                }
                try session.overrideOutputAudioPort(.none)

            case "Headset":
                if let headsetInput = session.availableInputs?.first(where: {
                    $0.portType == .headphones || $0.portType == .headsetMic
                }) {
                    try session.setPreferredInput(headsetInput)
                    logger.info("Audio route set to Headset: \(headsetInput.portName)")
                } else {
                    logger.warning("No Headset input available")
                }
                try session.overrideOutputAudioPort(.none)

            case "CarAudio":
                if let carInput = session.availableInputs?.first(where: {
                    $0.portType == .carAudio
                }) {
                    try session.setPreferredInput(carInput)
                    logger.info("Audio route set to CarAudio: \(carInput.portName)")
                } else {
                    logger.warning("No CarAudio input available")
                }
                try session.overrideOutputAudioPort(.none)

            case "Earpiece":
                try session.setPreferredInput(nil)
                try session.overrideOutputAudioPort(.none)
                logger.info("Audio route set to Earpiece")

            default:
                try session.setPreferredInput(nil)
                try session.overrideOutputAudioPort(.none)
                logger.info("Audio route set to default (Earpiece)")
            }

            // Notify route change after a short delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                self.notifyRouteChange()
            }

        } catch {
            logger.error("Failed to set audio route to \(route): \(error.localizedDescription)")
        }
    }

    private func determineCurrentRoute() -> String {
        let outputs = session.currentRoute.outputs
        logger.debug("Current route outputs: \(outputs.map { "\($0.portName) (\($0.portType.rawValue))" })")

        for output in outputs {
            switch output.portType {
            case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
                return "Bluetooth"
            case .builtInSpeaker:
                return "Speaker"
            case .builtInReceiver:
                return "Earpiece"
            case .headphones, .headsetMic:
                return "Headset"
            case .carAudio:
                return "CarAudio"
            default:
                continue
            }
        }
        return "Earpiece"
    }

    // MARK: - Audio Session Recovery

    private func recoverAudioSessionWithRetry() {
        audioActivationRetryCount += 1

        guard audioActivationRetryCount <= maxRetryCount else {
            logger.error("Audio session recovery failed after \(self.maxRetryCount) attempts")
            return
        }

        logger.info("Attempting audio session recovery (attempt \(self.audioActivationRetryCount)/\(self.maxRetryCount))")

        let rtcAudioSession = RTCAudioSession.sharedInstance()
        rtcAudioSession.lockForConfiguration()

        do {
            // Re-apply current configuration if available
            if let config = currentAudioConfiguration {
                try rtcAudioSession.setConfiguration(config)
                rtcAudioSession.isAudioEnabled = true
                logger.info("Audio session recovered successfully")

                // Restore preferred route if set
                if let preferredRoute = preferredAudioRoute {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        self.setAudioRoute(preferredRoute, force: true)
                    }
                }
            }
        } catch {
            logger.error("Audio session recovery failed: \(error.localizedDescription)")

            // Retry with exponential backoff
            let delay = Double(audioActivationRetryCount) * 0.5
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                self.recoverAudioSessionWithRetry()
            }
        }

        rtcAudioSession.unlockForConfiguration()
    }

    private func resetAudioSessionToDefault() {
        do {
            try session.setCategory(.ambient, mode: .default, options: [])
            try session.setActive(false, options: .notifyOthersOnDeactivation)
            logger.info("Audio session reset to default")
        } catch {
            logger.error("Failed to reset audio session: \(error.localizedDescription)")
        }
    }

    // MARK: - Notification Handlers

    private func handleRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        logger.info("Audio route change: \(reason.rawValue)")

        switch reason {
        case .newDeviceAvailable, .oldDeviceUnavailable:
            // Device change - update available devices and potentially recover session
            notifyDevicesChanged()
            notifyRouteChange()

            // If we lost our preferred device, clear the preference
            if reason == .oldDeviceUnavailable, let preferredRoute = preferredAudioRoute {
                let currentDevices = getAudioDevices().devices.map { $0.value }
                if !currentDevices.contains(preferredRoute) {
                    logger.info("Preferred device \(preferredRoute) no longer available, clearing preference")
                    self.preferredAudioRoute = nil
                }
            }

        case .categoryChange:
            // Category changed - might need to recover our configuration
            if isSessionActive {
                logger.warning("Audio session category changed during active call, attempting recovery")
                recoverAudioSessionWithRetry()
            }
            notifyRouteChange()

        case .override, .wakeFromSleep:
            // These can affect routing, notify changes
            notifyRouteChange()

        default:
            // Other route changes
            notifyRouteChange()
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        logger.info("Audio interruption: \(type.rawValue)")

        switch type {
        case .began:
            logger.info("Audio interruption began")
            // CallKit will handle the actual interruption, we just track state

        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                logger.info("Audio interruption ended with options: \(options.rawValue)")

                if options.contains(.shouldResume) && isSessionActive {
                    // Interruption ended and we should resume
                    logger.info("Resuming audio session after interruption")

                    // Give the system a moment to stabilize, then attempt recovery
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        self.audioQueue.async {
                            self.recoverAudioSessionWithRetry()
                        }
                    }
                }
            }

        @unknown default:
            logger.warning("Unknown interruption type: \(type.rawValue)")
        }
    }

    private func handleMediaServicesReset() {
        logger.warning("Media services were reset - reconfiguring audio")

        // Reset WebRTC audio
        setupWebRTCAudio()

        // If we had an active session, attempt to recover
        if isSessionActive {
            logger.info("Attempting to recover from media services reset")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                self.audioQueue.async {
                    self.recoverAudioSessionWithRetry()
                }
            }
        }

        // Notify about changes
        DispatchQueue.main.async {
            self.notifyRouteChange()
            self.notifyDevicesChanged()
        }
    }

    private func handleSilenceSecondaryAudioHint(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt,
              let type = AVAudioSession.SilenceSecondaryAudioHintType(rawValue: typeValue) else {
            return
        }

        logger.info("Secondary audio hint: \(type.rawValue)")
        // This is informational - we don't need to take action as CallKit handles this
    }

    // MARK: - Notification Methods

    private func notifyRouteChange() {
        let info = getAudioDevices()
        logger.debug("notifyRouteChange: currentRoute=\(info.currentRoute), devices=\(info.devices.map { $0.value })")
        DispatchQueue.main.async {
            self.delegate?.audioManager(self, didChangeRoute: info)
        }
    }

    private func notifyDevicesChanged() {
        let info = getAudioDevices()
        logger.debug("notifyDevicesChanged: currentRoute=\(info.currentRoute), devices=\(info.devices.map { $0.value })")
        DispatchQueue.main.async {
            self.delegate?.audioManager(self, didChangeDevices: info)
        }
    }
}
