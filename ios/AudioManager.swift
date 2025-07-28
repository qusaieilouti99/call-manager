import Foundation
import AVFoundation
import OSLog

protocol AudioManagerDelegate: AnyObject {
    func audioManager(_ manager: AudioManager, didChangeRoute routeInfo: AudioRoutesInfo)
    func audioManager(_ manager: AudioManager, didChangeDevices routeInfo: AudioRoutesInfo)
    func audioManagerDidActivateAudioSession(_ manager: AudioManager)
    func audioManagerDidDeactivateAudioSession(_ manager: AudioManager)
}

class AudioManager {
    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "AudioManager")
    private weak var delegate: AudioManagerDelegate?
    private var audioSession: AVAudioSession
    private var lastRouteInfo: AudioRoutesInfo?
    private var notificationObservers: [NSObjectProtocol] = []

    init(delegate: AudioManagerDelegate) {
        self.delegate = delegate
        self.audioSession = AVAudioSession.sharedInstance()

        logger.info("🔊 AudioManager initializing...")
        setupNotifications()
        logger.info("🔊 ✅ AudioManager initialized successfully")
    }

    deinit {
        logger.info("🔊 AudioManager deinitializing...")
        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        notificationObservers.removeAll()
    }

    private func setupNotifications() {
        logger.info("🔊 Setting up audio session notifications...")

        let routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: nil
        ) { [weak self] notification in
            self?.handleAudioRouteChanged(notification: notification)
        }
        notificationObservers.append(routeChangeObserver)

        let interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: nil
        ) { [weak self] notification in
            self?.handleAudioSessionInterrupted(notification: notification)
        }
        notificationObservers.append(interruptionObserver)

        logger.info("🔊 ✅ Audio session notifications setup completed")
    }

    // MARK: - Audio Session Configuration for CallKit

    func configureAudioSession(forCallType isVideo: Bool, isIncoming: Bool) {
        logger.info("🔊 Configuring audio session: isVideo=\(isVideo), isIncoming=\(isIncoming)...")

        let options: AVAudioSession.CategoryOptions
        if isVideo {
            options = [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker]
        } else {
            options = [.allowBluetooth, .allowBluetoothA2DP]
        }

        do {
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: options)
            logger.info("🔊 ✅ Audio session category/mode/options set for call")
        } catch {
            logger.error("🔊 ❌ Failed to configure audio session: \(error.localizedDescription)")
        }
    }

    // MARK: - Audio Route Management

    func getAudioDevices() -> AudioRoutesInfo {
        logger.debug("🔊 Getting available audio devices...")

        let currentRoute = audioSession.currentRoute
        var devices: [String] = ["Earpiece", "Speaker"] // Default options

        logger.debug("🔊 Current route inputs: \(currentRoute.inputs.map { $0.portType.rawValue })")
        logger.debug("🔊 Current route outputs: \(currentRoute.outputs.map { $0.portType.rawValue })")

        if let availableInputs = audioSession.availableInputs {
            logger.debug("🔊 Available inputs: \(availableInputs.map { $0.portType.rawValue })")

            for input in availableInputs {
                switch input.portType {
                case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
                    if !devices.contains("Bluetooth") {
                        devices.append("Bluetooth")
                        logger.debug("🔊 Added Bluetooth device")
                    }
                case .headphones, .headsetMic:
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
                logger.debug("🔊 Overriding to earpiece (built-in receiver)...")
                try audioSession.overrideOutputAudioPort(.none)
            case "Bluetooth", "Headset":
                logger.debug("🔊 Setting to Bluetooth/Headset (system managed via .none)...")
                try audioSession.overrideOutputAudioPort(.none)
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
    }

    // MARK: - Audio Session Activation/Deactivation from CallKit

    func activateAudioSession() {
        logger.info("🔊 Audio session activation requested by CallKit...")
        do {
            try audioSession.setActive(true)
            logger.info("🔊 ✅ Audio session activated successfully")
            delegate?.audioManagerDidActivateAudioSession(self)
        } catch {
            logger.error("🔊 ❌ Failed to activate audio session: \(error.localizedDescription)")
        }
    }

    func deactivateAudioSession() {
        logger.info("🔊 Audio session deactivation requested by CallKit...")
        do {
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            logger.info("🔊 ✅ Audio session deactivated successfully")
            delegate?.audioManagerDidDeactivateAudioSession(self)
        } catch {
            logger.error("🔊 ❌ Failed to deactivate audio session: \(error.localizedDescription)")
        }
    }

    // MARK: - Internal Helper Methods

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
            case .headphones, .headsetMic:
                return "Headset"
            case .builtInReceiver:
                return "Earpiece"
            default:
                logger.debug("🔊 Unhandled output type: \(routeType.rawValue)")
                continue
            }
        }

        logger.debug("🔊 No specific route found, defaulting to Earpiece")
        return "Earpiece"
    }

    private func notifyRouteChange() {
        logger.debug("🔊 Notifying delegate about route change...")
        let routeInfo = getAudioDevices()
        self.delegate?.audioManager(self, didChangeRoute: routeInfo)
    }

    private func notifyDeviceChange() {
        logger.debug("🔊 Notifying delegate about device change...")
        let routeInfo = getAudioDevices()
        self.delegate?.audioManager(self, didChangeDevices: routeInfo)
    }

    // MARK: - Notification Handlers

    private func handleAudioRouteChanged(notification: Notification) {
        logger.info("🔊 Audio route changed notification received")

        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            logger.warning("🔊 ⚠️ Could not parse route change reason")
            return
        }

        logger.info("🔊 Route change reason: \(reason.rawValue)")

        switch reason {
        case AVAudioSession.RouteChangeReason.newDeviceAvailable, AVAudioSession.RouteChangeReason.oldDeviceUnavailable:
            logger.info("🔊 Audio device availability changed: \(reason.rawValue)") // Use .rawValue
            notifyDeviceChange()
        case AVAudioSession.RouteChangeReason.override, AVAudioSession.RouteChangeReason.categoryChange:
            logger.info("🔊 Audio route override or category change: \(reason.rawValue)") // Use .rawValue
            notifyRouteChange()
        case AVAudioSession.RouteChangeReason.wakeFromSleep, AVAudioSession.RouteChangeReason.noSuitableRouteForCategory:
            logger.info("🔊 Session recovered or no suitable route: \(reason.rawValue)") // Use .rawValue
            notifyRouteChange()
        default:
            logger.info("🔊 Other audio route change reason: \(reason.rawValue)") // Use .rawValue
            notifyRouteChange()
        }
    }

    private func handleAudioSessionInterrupted(notification: Notification) {
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
                }
            }
        @unknown default:
            logger.warning("🔊 ⚠️ Unknown interruption type")
            break
        }
    }
}
