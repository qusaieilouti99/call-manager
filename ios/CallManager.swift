import Foundation
import NitroModules
import OSLog
import UIKit

/// The public-facing bridge class that implements the `HybridCallManagerSpec` protocol.
/// Its responsibility is to translate calls from the JavaScript layer into commands
/// for the `CallEngine` singleton. It acts as a thin, safe interface to the native call logic.
public class CallManager: HybridCallManagerSpec {
    private let logger = Logger(
        subsystem: "com.qusaieilouti99.callmanager",
        category: "CallManager"
    )

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
        return CallEngine.shared.getAudioDevices()
    }

    public func setAudioRoute(route: String) throws {
        logger.info("🎯 setAudioRoute ▶ js → native: \(route)")
        CallEngine.shared.setAudioRoute(route: route)
    }

    public func keepScreenAwake(keepAwake: Bool) throws {
        logger.info("🎯 keepScreenAwake ▶ js → native: \(keepAwake)")
        CallEngine.shared.setIdleTimerDisabled(shouldDisable: keepAwake)
    }

    public func addListener(listener: @escaping (CallEventType, String) -> Void) throws
        -> () -> Void
    {
        logger.info("🎯 addListener ▶ js → native")
        CallEngine.shared.setEventHandler { event, payload in
            self.logger.debug("🎯 event \(event.stringValue), payload.len=\(payload.count)")
            listener(event, payload)
        }
        // Return a closure that will be called by the JS layer to unsubscribe.
        return {
            self.logger.info("🎯 removeListener ▶ js → native")
            CallEngine.shared.setEventHandler(nil)
        }
    }

    public func startOutgoingCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String?
    ) throws {
        logger.info("🎯 startOutgoingCall ▶ js → native: \(callId), type=\(callType)")
        CallEngine.shared.startOutgoingCall(
            callId: callId,
            callType: callType,
            targetName: targetName,
            metadata: metadata
        )
    }

    public func reportIncomingCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String?,
        token: String?,
        rejectEndpoint: String?
    ) throws {
        logger.info("🎯 reportIncomingCall ▶ js → native: \(callId), type=\(callType)")
        CallEngine.shared.reportIncomingCall(
            callId: callId,
            callType: callType,
            displayName: targetName,
            pictureUrl: nil,
            metadata: metadata,
            completion: nil
        )
    }

    public func startCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String?
    ) throws {
        logger.info("🎯 startCall ▶ js → native: \(callId), type=\(callType)")
        CallEngine.shared.startCall(
            callId: callId,
            callType: callType,
            targetName: targetName,
            metadata: metadata
        )
    }

    /// This function is called from JS to signal that a remote party has answered an outgoing call.
    public func callAnswered(callId: String) throws {
        logger.info("🎯 callAnswered ▶ js → native: \(callId)")
        CallEngine.shared.connectOutgoingCall(callId: callId)
    }

    public func setOnHold(callId: String, onHold: Bool) throws {
        logger.info("🎯 setOnHold ▶ js → native: \(callId), onHold=\(onHold)")
        CallEngine.shared.setOnHold(callId: callId, onHold: onHold)
    }

    public func setMuted(callId: String, muted: Bool) throws {
        logger.info("🎯 setMuted ▶ js → native: \(callId), muted=\(muted)")
        CallEngine.shared.setMuted(callId: callId, muted: muted)
    }

    public func updateDisplayCallInformation(callId: String, callerName: String) throws {
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
            self.logger.info("🎯 voip token received")
            listener(token)
        }
        return {
            self.logger.info("🎯 unregisterVoIPTokenListener ▶ js → native")
            VoIPTokenManager.shared.unregisterTokenListener()
        }
    }

    public func hasActiveCall() throws -> Bool {
        logger.info("🎯 hasActiveCall ▶ js → native")
        return CallEngine.shared.hasActiveCalls()
    }

    public func requestOverlayPermissionAndroid() throws -> Bool {
        // This is an Android-specific method, so we provide a no-op success case for iOS.
        return true
    }

    public func hasOverlayPermissionAndroid() throws -> Bool {
        // This is an Android-specific method, so we provide a no-op success case for iOS.
        return true
    }
}
