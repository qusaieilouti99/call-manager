import Foundation
import AVFoundation
import OSLog

protocol AudioManagerDelegate: AnyObject {
    func audioManager(_ manager: AudioManager, didChangeRoute routeInfo: AudioRoutesInfo)
    func audioManager(_ manager: AudioManager, didChangeDevices routeInfo: AudioRoutesInfo)
}

class AudioManager: NSObject {
    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "AudioManager")
    private weak var delegate: AudioManagerDelegate?
    private var audioSession: AVAudioSession
    private var lastRouteInfo: AudioRoutesInfo?

    init(delegate: AudioManagerDelegate) {
        self.delegate = delegate
        self.audioSession = AVAudioSession.sharedInstance()
        super.init()

        logger.info("🔊 AudioManager initializing...")
        setupNotifications()
        logger.info("🔊 ✅ AudioManager initialized successfully")
    }

    deinit {
        logger.info("🔊 AudioManager deinitializing...")
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Setup
    private func setupNotifications() {
        logger.info("🔊 Setting up audio session notifications...")

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioRouteChanged),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioSessionInterrupted),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )

        logger.info("🔊 ✅ Audio session notifications setup completed")
    }

    // MARK: - Public Methods
    func configureForIncomingCall() {
        logger.info("🔊 Configuring audio session for incoming call...")

        do {
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
            try audioSession.setActive(true)
            logger.info("🔊 ✅ Audio session configured for incoming call")
        } catch {
            logger.error("🔊 ❌ Failed to configure audio session for incoming call: \(error.localizedDescription)")
        }
    }

    func configureForOutgoingCall(isVideo: Bool) {
        logger.info("🔊 Configuring audio session for outgoing call (video: \(isVideo))...")

        do {
            let options: AVAudioSession.CategoryOptions = isVideo ?
                [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker] :
                [.allowBluetooth, .allowBluetoothA2DP]

            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: options)
            try audioSession.setActive(true)
            logger.info("🔊 ✅ Audio session configured for outgoing call (video: \(isVideo))")
        } catch {
            logger.error("🔊 ❌ Failed to configure audio session for outgoing call: \(error.localizedDescription)")
        }
    }

    func configureForActiveCall(isVideo: Bool) {
        logger.info("🔊 Configuring audio session for active call (video: \(isVideo))...")

        do {
            let options: AVAudioSession.CategoryOptions = isVideo ?
                [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker] :
                [.allowBluetooth, .allowBluetoothA2DP]

            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: options)
            try audioSession.setActive(true)
            logger.info("🔊 ✅ Audio session configured for active call (video: \(isVideo))")
        } catch {
            logger.error("🔊 ❌ Failed to configure audio session for active call: \(error.localizedDescription)")
        }
    }

    func getAudioDevices() -> AudioRoutesInfo {
        logger.debug("🔊 Getting available audio devices...")

        let currentRoute = audioSession.currentRoute
        var devices: [String] = ["Earpiece", "Speaker"]

        logger.debug("🔊 Current route inputs: \(currentRoute.inputs.map { $0.portType.rawValue })")
        logger.debug("🔊 Current route outputs: \(currentRoute.outputs.map { $0.portType.rawValue })")

        // Check for available inputs/outputs
        if let availableInputs = audioSession.availableInputs {
            logger.debug("🔊 Available inputs: \(availableInputs.map { $0.portType.rawValue })")

            for input in availableInputs {
                switch input.portType {
                case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
                    if !devices.contains("Bluetooth") {
                        devices.append("Bluetooth")
                        logger.debug("🔊 Added Bluetooth device")
                    }
                case .headphones, .headsetMic, .wiredHeadphones:
                    if !devices.contains("Headset") {
                        devices.append("Headset")
                        logger.debug("🔊 Added Headset device")
                    }
                default:
                    logger.debug("🔊 Other input type: \(input.portType.rawValue)")
                    break
                }
            }
        }

        // Determine current route
        let currentRouteString = getCurrentAudioRoute()

        let routeInfo = AudioRoutesInfo(devices: devices, currentRoute: currentRouteString)
        lastRouteInfo = routeInfo

        logger.info("🔊 Available audio devices: \(devices), current: \(currentRouteString)")
        return routeInfo
    }

    func setAudioRoute(_ route: String) {
        logger.info("🔊 Setting audio route to: \(route)")

        let previousRoute = getCurrentAudioRoute()
        logger.debug("🔊 Previous route: \(previousRoute)")

        do {
            switch route {
            case "Speaker":
                logger.debug("🔊 Overriding to speaker...")
                try audioSession.overrideOutputAudioPort(.speaker)
            case "Earpiece":
                logger.debug("🔊 Overriding to earpiece...")
                try audioSession.overrideOutputAudioPort(.none)
            case "Bluetooth":
                logger.debug("🔊 Setting to Bluetooth (system managed)...")
                try audioSession.overrideOutputAudioPort(.none)
                // Bluetooth routing is handled automatically by the system
            case "Headset":
                logger.debug("🔊 Setting to Headset (system managed)...")
                try audioSession.overrideOutputAudioPort(.none)
                // Headset routing is handled automatically by the system
            default:
                logger.warning("🔊 ⚠️ Unknown audio route: \(route)")
                return
            }

            let newRoute = getCurrentAudioRoute()
            logger.info("🔊 Audio route changed: \(previousRoute) → \(newRoute)")

            if previousRoute != newRoute {
                notifyRouteChange()
            }
        } catch {
            logger.error("🔊 ❌ Failed to set audio route to \(route): \(error.localizedDescription)")
        }
    }

    func setMuted(_ muted: Bool) {
        logger.info("🔊 Mute state changed to: \(muted)")
        // Note: Muting is typically handled by CallKit automatically
        // This is here for consistency with Android implementation
    }

    func cleanup() {
        logger.info("🔊 Cleaning up audio session...")

        do {
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            logger.info("🔊 ✅ Audio session deactivated successfully")
        } catch {
            logger.error("🔊 ❌ Failed to deactivate audio session: \(error.localizedDescription)")
        }
    }

    // MARK: - Private Methods
    private func getCurrentAudioRoute() -> String {
        let currentRoute = audioSession.currentRoute

        for output in currentRoute.outputs {
            let routeType = output.portType
            logger.debug("🔊 Checking output port: \(routeType.rawValue)")

            switch routeType {
            case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
                return "Bluetooth"
            case .builtInSpeaker:
                return "Speaker"
            case .headphones, .headsetMic, .wiredHeadphones:
                return "Headset"
            case .builtInReceiver:
                return "Earpiece"
            default:
                continue
            }
        }

        logger.debug("🔊 No specific route found, defaulting to Earpiece")
        return "Earpiece" // Default fallback
    }

    private func notifyRouteChange() {
        logger.debug("🔊 Notifying delegate about route change...")
        let routeInfo = getAudioDevices()
        delegate?.audioManager(self, didChangeRoute: routeInfo)
    }

    private func notifyDeviceChange() {
        logger.debug("🔊 Notifying delegate about device change...")
        let routeInfo = getAudioDevices()
        delegate?.audioManager(self, didChangeDevices: routeInfo)
    }

    // MARK: - Notification Handlers
    @objc private func audioRouteChanged(notification: Notification) {
        logger.info("🔊 Audio route changed notification received")

        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            logger.warning("🔊 ⚠️ Could not parse route change reason")
            return
        }

        logger.info("🔊 Route change reason: \(reason)")

        switch reason {
        case .newDeviceAvailable, .oldDeviceUnavailable:
            logger.info("🔊 Audio device availability changed: \(reason)")
            notifyDeviceChange()
        case .override, .categoryChange:
            logger.info("🔊 Audio route override or category change: \(reason)")
            notifyRouteChange()
        default:
            logger.info("🔊 Other audio route change reason: \(reason)")
            notifyRouteChange()
        }
    }

    @objc private func audioSessionInterrupted(notification: Notification) {
        logger.info("🔊 Audio session interrupted notification received")

        guard let userInfo = notification.userInfo,
              let interruptionTypeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let interruptionType = AVAudioSession.InterruptionType(rawValue: interruptionTypeValue) else {
            logger.warning("🔊 ⚠️ Could not parse interruption type")
            return
        }

        switch interruptionType {
        case .began:
            logger.info("🔊 Audio session interruption began")
        case .ended:
            logger.info("🔊 Audio session interruption ended")
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    logger.info("🔊 Should resume audio session")
                    // Audio session will be reactivated by CallKit
                }
            }
        @unknown default:
            logger.warning("🔊 ⚠️ Unknown interruption type")
            break
        }
    }
}
