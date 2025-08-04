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
    private var pendingRoute: String?
    private var isSessionActive: Bool = false

    init(delegate: AudioManagerDelegate) {
        self.delegate = delegate
        logger.info("AudioManager init")
        setupNotifications()
        setupInitialAudioSession()
        setupWebRTCAudio()
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    private func setupNotifications() {
        let nc = NotificationCenter.default
        observers.append(
            nc.addObserver(
                forName: AVAudioSession.routeChangeNotification,
                object: nil,
                queue: nil
            ) { [weak self] n in self?.handleRouteChange(n) }
        )
        observers.append(
            nc.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: nil,
                queue: nil
            ) { [weak self] n in self?.handleInterruption(n) }
        )
        observers.append(
            nc.addObserver(
                forName: AVAudioSession.mediaServicesWereResetNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in self?.handleMediaServicesReset() }
        )
        logger.info("AudioManager notifications set")
    }

    private func setupInitialAudioSession() {
        logger.info("Setting up initial audio session")
        do {
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            logger.info("Initial audio session configured")
        } catch {
            logger.error("Failed to setup initial audio session: \(error.localizedDescription)")
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
        logger.info("CallKit didActivate audioSession, configuring for VoIP")
        isSessionActive = true

        // Configure WebRTC audio session (the right way)
        let rtcAudioSession = RTCAudioSession.sharedInstance()
        rtcAudioSession.lockForConfiguration()
        let config = RTCAudioSessionConfiguration.webRTC()
        config.categoryOptions = [
            .allowBluetoothA2DP,
            .duckOthers,
            .allowBluetooth,
            .mixWithOthers
        ]
        do {
            try rtcAudioSession.setConfiguration(config)
            try rtcAudioSession.setActive(true)
        } catch {
            logger.error("Failed to configure RTCAudioSession: \(error.localizedDescription)")
        }
        rtcAudioSession.isAudioEnabled = true
        rtcAudioSession.unlockForConfiguration()

        delegate?.audioManagerDidActivateAudioSession(self)

        // Handle pending route after proper activation
        if let pending = pendingRoute {
            logger.info("Setting pending audio route after activation: \(pending)")
            pendingRoute = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.setAudioRoute(pending, force: true)
            }
        }

        // Always notify about initial route after activation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.notifyRouteChange()
        }
    }

    func callKitDidDeactivateAudioSession(_ audioSession: AVAudioSession) {
        logger.info("CallKit didDeactivate audioSession, cleaning up")
        isSessionActive = false

        let rtcAudioSession = RTCAudioSession.sharedInstance()
        rtcAudioSession.lockForConfiguration()
        rtcAudioSession.isAudioEnabled = false
        rtcAudioSession.audioSessionDidDeactivate(audioSession)
        rtcAudioSession.unlockForConfiguration()

        // Reset to default audio configuration
        do {
            try audioSession.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            logger.info("Audio session reset to default")
        } catch {
            logger.error("Failed to reset audio session: \(error.localizedDescription)")
        }

        delegate?.audioManagerDidDeactivateAudioSession(self)
        notifyRouteChange()
    }

    // MARK: - Audio Device Management

    func getAudioDevices() -> AudioRoutesInfo {
        let current = session.currentRoute
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
                default:
                    break
                }
            }
        }

        let route = determineCurrentRoute()
        let deviceHolders = devices.map { StringHolder(value: $0) }
        logger.info("getAudioDevices: route=\(route), devices=\(devices)")
        return AudioRoutesInfo(devices: deviceHolders, currentRoute: route)
    }

    func setAudioRoute(_ route: String, force: Bool = false) {
        logger.info("setAudioRoute: \(route), isSessionActive=\(self.isSessionActive), force=\(force)")

        if !isSessionActive && !force {
            logger.info("Session not active, queueing route: \(route)")
            pendingRoute = route
            return
        }

        pendingRoute = nil

        do {
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
            case "Earpiece":
                try session.setPreferredInput(nil)
                try session.overrideOutputAudioPort(.none)
                logger.info("Audio route set to Earpiece")
            default:
                try session.setPreferredInput(nil)
                try session.overrideOutputAudioPort(.none)
                logger.info("Audio route set to default")
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.notifyRouteChange()
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
            default:
                continue
            }
        }
        return "Earpiece"
    }

    private func notifyRouteChange() {
        let info = getAudioDevices()
        logger.info("notifyRouteChange: currentRoute=\(info.currentRoute), devices=\(info.devices.map { $0.value })")
        delegate?.audioManager(self, didChangeRoute: info)
    }

    // MARK: - Notification Handlers

    private func handleRouteChange(_ notification: Notification) {
        logger.info("Audio route change notification")
        notifyRouteChange()
    }

    private func handleInterruption(_ notification: Notification) {
        logger.info("Audio interruption notification")
        // Handle as needed
    }

    private func handleMediaServicesReset() {
        logger.warning("Media services were reset - reconfiguring audio")
        setupWebRTCAudio()
        notifyRouteChange()
    }
}
