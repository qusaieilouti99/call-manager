import Foundation
import NitroModules
import OSLog
import UIKit

public class CallManager: HybridCallManagerSpec {
  private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager",
                              category: "CallManager")

  public override init() {
    super.init()
    logger.info("🎯 CallManager hybrid module init")
    CallEngine.shared.initialize()
    logger.info("🎯 ✅ CallManager initialized")
  }

  public func endCall(callId: String) throws {
    logger.info("🎯 endCall ▶ js → native: \(callId)")
    CallEngine.shared.endCall(callId: callId)
  }

  public func endAllCalls() throws {
    logger.info("🎯 endAllCalls ▶ js → native")
    CallEngine.shared.endAllCalls()
  }

  public func silenceRingtone() throws {
    logger.info("🎯 silenceRingtone ▶ js → native (handled by CallKit)")
  }

  public func getAudioDevices() throws -> AudioRoutesInfo {
    logger.info("🎯 getAudioDevices ▶ js → native")
    let info = CallEngine.shared.getAudioDevices()
    logger.info("🎯 getAudioDevices ✓ devices=\(info.devices), current=\(info.currentRoute)")
    return info
  }

  public func setAudioRoute(route: String) throws {
    logger.info("🎯 setAudioRoute ▶ js → native: \(route)")
    CallEngine.shared.setAudioRoute(route: route)
  }

  public func keepScreenAwake(keepAwake: Bool) throws {
    logger.info("🎯 keepScreenAwake ▶ js → native: \(keepAwake)")
    DispatchQueue.main.async {
      UIApplication.shared.isIdleTimerDisabled = keepAwake
      self.logger.info("🎯 isIdleTimerDisabled = \(keepAwake)")
    }
  }

  public func addListener(listener: @escaping (CallEventType, String) -> Void) throws
    -> () -> Void
  {
    logger.info("🎯 addListener ▶ js → native")
    CallEngine.shared.setEventHandler { event, payload in
      self.logger.debug("🎯 event \(event.stringValue), payload.len=\(payload.count)")
      listener(event, payload)
    }
    return {
      self.logger.info("🎯 removeListener ▶ js → native")
      CallEngine.shared.setEventHandler(nil)
    }
  }

  public func startOutgoingCall(callId: String,
                                callType: String,
                                targetName: String,
                                metadata: String?) throws
  {
    logger.info("🎯 startOutgoingCall ▶ js → native: \(callId), type=\(callType)")
    if let m = metadata { logger.debug("🎯 metadata.len=\(m.count)") }
    CallEngine.shared.startOutgoingCall(
      callId: callId,
      callType: callType,
      targetName: targetName,
      metadata: metadata
    )
  }

  public func startCall(callId: String,
                        callType: String,
                        targetName: String,
                        metadata: String?) throws
  {
    logger.info("🎯 startCall ▶ js → native: \(callId), type=\(callType)")
    if let m = metadata { logger.debug("🎯 metadata.len=\(m.count)") }
    CallEngine.shared.startCall(
      callId: callId,
      callType: callType,
      targetName: targetName,
      metadata: metadata
    )
  }

  public func callAnswered(callId: String) throws {
    logger.info("🎯 callAnswered ▶ js → native: \(callId)")
    CallEngine.shared.answerCall(callId: callId)
  }

  public func setOnHold(callId: String, onHold: Bool) throws {
    logger.info("🎯 setOnHold ▶ js → native: \(callId), onHold=\(onHold)")
    CallEngine.shared.setOnHold(callId: callId, onHold: onHold)
  }

  public func setMuted(callId: String, muted: Bool) throws {
    logger.info("🎯 setMuted ▶ js → native: \(callId), muted=\(muted)")
    CallEngine.shared.setMuted(callId: callId, muted: muted)
  }

  public func updateDisplayCallInformation(callId: String,
                                           callerName: String) throws
  {
    logger.info("🎯 updateDisplayCallInfo ▶ js → native: \(callId), name=\(callerName)")
    CallEngine.shared.updateDisplayCallInformation(
      callId: callId,
      callerName: callerName
    )
  }

  public func registerVoIPTokenListener(listener: @escaping (String) -> Void)
    throws -> () -> Void
  {
    logger.info("🎯 registerVoIPTokenListener ▶ js → native")
    VoIPTokenManager.shared.registerTokenListener { token in
      self.logger.info("🎯 voip token.len=\(token.count)")
      listener(token)
    }
    return {
      self.logger.info("🎯 unregisterVoIPTokenListener ▶ js → native")
      VoIPTokenManager.shared.unregisterTokenListener()
    }
  }
}
