import Foundation
import AVFoundation
import OSLog

protocol AudioManagerDelegate: AnyObject {
  func audioManager(_ manager: AudioManager,
                    didChangeRoute routeInfo: AudioRoutesInfo)
  func audioManager(_ manager: AudioManager,
                    didChangeDevices routeInfo: AudioRoutesInfo)
  func audioManagerDidActivateAudioSession(_ manager: AudioManager)
  func audioManagerDidDeactivateAudioSession(_ manager: AudioManager)
}

class AudioManager {
  private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager",
                              category: "CallManager")
  private weak var delegate: AudioManagerDelegate?
  private let session = AVAudioSession.sharedInstance()
  private var observers: [NSObjectProtocol] = []

  init(delegate: AudioManagerDelegate) {
    self.delegate = delegate
    logger.info("AudioManager init")
    setupNotifications()
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
    logger.info("AudioManager notifications set")
  }

  func configureAudioSession(forCallType isVideo: Bool,
                             isIncoming: Bool) {
    logger.info("configureAudioSession: video=\(isVideo), incoming=\(isIncoming)")
    var opts: AVAudioSession.CategoryOptions = [.allowBluetooth, .allowBluetoothA2DP]
    if isVideo || isIncoming { opts.insert(.defaultToSpeaker) }
    do {
      try session.setCategory(.playAndRecord,
                              mode: .voiceChat,
                              options: opts)
      logger.info("audioSession category set")
    } catch {
      logger.error("setCategory failed: \(error.localizedDescription)")
    }
  }

  func activateAudioSession() {
    logger.info("activateAudioSession")
    do {
      try session.setActive(true)
      delegate?.audioManagerDidActivateAudioSession(self)
    } catch {
      logger.error("activate failed: \(error.localizedDescription)")
    }
  }

  func deactivateAudioSession() {
    logger.info("deactivateAudioSession")
    do {
      try session.setActive(false, options: .notifyOthersOnDeactivation)
      delegate?.audioManagerDidDeactivateAudioSession(self)
    } catch {
      logger.error("deactivate failed: \(error.localizedDescription)")
    }
  }

  func getAudioDevices() -> AudioRoutesInfo {
    let current = session.currentRoute
    var devices = ["Earpiece", "Speaker"]
    if let inputs = session.availableInputs {
      for i in inputs {
        switch i.portType {
        case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
          if !devices.contains("Bluetooth") { devices.append("Bluetooth") }
        case .headphones, .headsetMic:
          if !devices.contains("Headset") { devices.append("Headset") }
        default: break
        }
      }
    }
    let route = determineCurrentRoute()
    let info = AudioRoutesInfo(devices: devices, currentRoute: route)
    return info
  }

  func setAudioRoute(_ route: String) {
    logger.info("setAudioRoute: \(route)")
    do {
      switch route {
      case "Speaker":
        try session.overrideOutputAudioPort(.speaker)
      default:
        try session.overrideOutputAudioPort(.none)
      }
    } catch {
      logger.error("overrideOutputAudioPort failed: \(error.localizedDescription)")
    }
  }

  private func determineCurrentRoute() -> String {
    for o in session.currentRoute.outputs {
      switch o.portType {
      case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE:
        return "Bluetooth"
      case .builtInSpeaker:
        return "Speaker"
      case .builtInReceiver:
        return "Earpiece"
      case .headphones, .headsetMic:
        return "Headset"
      default: continue
      }
    }
    return "Earpiece"
  }

  private func handleRouteChange(_ n: Notification) {
    logger.info("routeChange notification")
    guard let rv = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
          let reason = AVAudioSession.RouteChangeReason(rawValue: rv)
    else { return }
    let info = getAudioDevices()
    switch reason {
    case .newDeviceAvailable, .oldDeviceUnavailable:
      delegate?.audioManager(self, didChangeDevices: info)
    default:
      delegate?.audioManager(self, didChangeRoute: info)
    }
  }

  private func handleInterruption(_ n: Notification) {
    logger.info("interruption notification")
    guard let tv = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
          let type = AVAudioSession.InterruptionType(rawValue: tv)
    else { return }
    switch type {
    case .began:
      logger.info("interruption began")
    case .ended:
      logger.info("interruption ended")
      if let ov = n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt {
        let opts = AVAudioSession.InterruptionOptions(rawValue: ov)
        if opts.contains(.shouldResume) {
          activateAudioSession()
        }
      }
    @unknown default: break
    }
  }
}
