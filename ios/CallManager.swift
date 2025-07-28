import Foundation
import NitroModules
import OSLog

public class CallManager: HybridCallManagerSpec {
    private let logger = Logger(subsystem: "com.qusaieilouti99.callmanager", category: "CallManager")

    public override init() {
        super.init()
        logger.info("🎯 CallManager Nitro module initializing...")
        ensureInitialized()
        logger.info("🎯 ✅ CallManager Nitro module initialized")
    }

    private func ensureInitialized() {
        logger.debug("🎯 Ensuring CallEngine is initialized...")
        CallEngine.shared.initialize()
    }

    public func endCall(callId: String) throws {
        logger.info("🎯📞 endCall requested for callId: \(callId)")
        ensureInitialized()
        CallEngine.shared.endCall(callId: callId)
        logger.info("🎯📞 ✅ endCall completed for callId: \(callId)")
    }

    public func endAllCalls() throws {
        logger.info("🎯📞 endAllCalls requested")
        ensureInitialized()
        CallEngine.shared.endAllCalls()
        logger.info("🎯📞 ✅ endAllCalls completed")
    }

    public func silenceRingtone() throws {
        logger.info("🎯🔇 silenceRingtone requested")
        ensureInitialized()
        logger.info("🎯🔇 ✅ silenceRingtone completed (handled by CallKit)")
    }

    public func getAudioDevices() throws -> AudioRoutesInfo {
        logger.info("🎯🔊 getAudioDevices requested")
        ensureInitialized()
        let result = CallEngine.shared.getAudioDevices()
        logger.info("🎯🔊 ✅ getAudioDevices completed: \(result.devices.count) devices, current: \(result.currentRoute)")
        return result
    }

    public func setAudioRoute(route: String) throws {
        logger.info("🎯🔊 setAudioRoute requested for route: \(route)")
        ensureInitialized()
        CallEngine.shared.setAudioRoute(route)
        logger.info("🎯🔊 ✅ setAudioRoute completed for route: \(route)")
    }

    public func keepScreenAwake(keepAwake: Bool) throws {
        logger.info("🎯💡 keepScreenAwake requested: \(keepAwake)")
        ensureInitialized()
        logger.info("🎯💡 ✅ keepScreenAwake completed (handled by CallKit)")
    }

    public func addListener(listener: @escaping (CallEventType, String) -> Void) throws -> () -> Void {
        logger.info("🎯📡 addListener called")
        ensureInitialized()

        CallEngine.shared.setEventHandler { [weak self] eventType, payload in
            self?.logger.debug("🎯📡 Event emitted: \(eventType), payload length: \(payload.count)")
            listener(eventType, payload)
        }

        logger.info("🎯📡 ✅ Event handler registered")

        return { [weak self] in
            self?.logger.info("🎯📡 Removing event handler...")
            CallEngine.shared.setEventHandler(nil)
            self?.logger.info("🎯📡 ✅ Event handler removed")
        }
    }

    public func startOutgoingCall(callId: String, callType: String, targetName: String, metadata: String?) throws {
        logger.info("🎯📞 startOutgoingCall requested: callId=\(callId), callType=\(callType), targetName=\(targetName)")
        if let metadata = metadata {
            logger.debug("🎯📞 Metadata length: \(metadata.count)")
        }
        ensureInitialized()
        CallEngine.shared.startOutgoingCall(callId: callId, callType: callType, targetName: targetName, metadata: metadata)
        logger.info("🎯📞 ✅ startOutgoingCall completed for callId: \(callId)")
    }

    public func startCall(callId: String, callType: String, targetName: String, metadata: String?) throws {
        logger.info("🎯📞 startCall requested: callId=\(callId), callType=\(callType), targetName=\(targetName)")
        if let metadata = metadata {
            logger.debug("🎯📞 Metadata length: \(metadata.count)")
        }
        ensureInitialized()
        CallEngine.shared.startCall(callId: callId, callType: callType, targetName: targetName, metadata: metadata)
        logger.info("🎯📞 ✅ startCall completed for callId: \(callId)")
    }

    public func callAnswered(callId: String) throws {
        logger.info("🎯📞 callAnswered (from JS) requested for callId: \(callId)")
        ensureInitialized()
        CallEngine.shared.callAnsweredFromJS(callId: callId)
        logger.info("🎯📞 ✅ callAnswered completed for callId: \(callId)")
    }

    public func setOnHold(callId: String, onHold: Bool) throws {
        logger.info("🎯📞 setOnHold requested for callId: \(callId), onHold: \(onHold)")
        ensureInitialized()
        CallEngine.shared.setOnHold(callId: callId, onHold: onHold)
        logger.info("🎯📞 ✅ setOnHold completed for callId: \(callId)")
    }

    public func setMuted(callId: String, muted: Bool) throws {
        logger.info("🎯🔇 setMuted requested for callId: \(callId), muted: \(muted)")
        ensureInitialized()
        CallEngine.shared.setMuted(callId: callId, muted: muted)
        logger.info("🎯🔇 ✅ setMuted completed for callId: \(callId)")
    }

    public func updateDisplayCallInformation(callId: String, callerName: String) throws {
        logger.info("🎯📲 updateDisplayCallInformation requested for callId: \(callId), callerName: \(callerName)")
        ensureInitialized()
        CallEngine.shared.updateDisplayCallInformation(callId: callId, callerName: callerName)
        logger.info("🎯📲 ✅ updateDisplayCallInformation completed for callId: \(callId)")
    }

    public func registerVoIPTokenListener(listener: @escaping (String) -> Void) throws -> () -> Void {
        logger.info("🎯🔑 registerVoIPTokenListener called")
        ensureInitialized()

        VoIPTokenManager.shared.registerTokenListener { [weak self] token in
            self?.logger.info("🎯🔑 VoIP token received, length: \(token.count)")
            listener(token)
        }

        logger.info("🎯🔑 ✅ VoIP token listener registered")

        return { [weak self] in
            self?.logger.info("🎯🔑 Removing VoIP token listener...")
            VoIPTokenManager.shared.unregisterTokenListener()
            self?.logger.info("🎯🔑 ✅ VoIP token listener removed")
        }
    }
}
