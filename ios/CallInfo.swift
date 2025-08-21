import Foundation
import OSLog

/// A simple, immutable data structure to hold all relevant information about a single call.
/// Using a struct ensures that this data is passed by value, preventing unintended side effects.
public struct CallInfo {
    // MARK: - Properties

    /// The unique identifier for the call (UUID).
    let callId: String
    /// The type of call, e.g., "Audio" or "Video". Used to determine audio configuration.
    let callType: String
    /// The name of the caller/callee to display in the UI.
    var displayName: String
    /// An optional URL for the caller's avatar image.
    let pictureUrl: String?
    /// The current state of the call (e.g., incoming, active, held).
    var state: CallState
    /// The timestamp when the call object was created.
    let timestamp: TimeInterval
    /// A flag indicating if the call was put on hold by the system (e.g., for another incoming call).
    var wasHeldBySystem: Bool
    /// A flag indicating if the user has muted their microphone.
    var isMuted: Bool

    private static let logger = Logger(
        subsystem: "com.qusaieilouti99.callmanager",
        category: "CallInfo"
    )

    // MARK: - Initializer

    init(
        callId: String,
        callType: String,
        displayName: String,
        pictureUrl: String?,
        state: CallState
    ) {
        self.callId = callId
        self.callType = callType
        self.displayName = displayName
        self.pictureUrl = pictureUrl
        self.state = state
        self.timestamp = Date().timeIntervalSince1970
        self.wasHeldBySystem = false
        self.isMuted = false
        Self.logger.info("CallInfo created: \(callId), state: \(state.stringValue)")
    }

    // MARK: - Methods

    /// Safely updates the state of the call and logs the change.
    /// This is a `mutating` function because it modifies the struct's own properties.
    mutating func updateState(_ newState: CallState) {
        let old = state
        state = newState
        // Capture the immutable `callId` before the logging closure.
        // This is a Swift best practice to avoid a compiler warning about capturing
        // a mutable `self` in an escaping closure like the logger's.
        let id = self.callId
        Self.logger
            .info("Call [\(id)] state changed: \(old.stringValue) -> \(newState.stringValue)")
    }

    /// Converts the struct to a dictionary for serialization to JavaScript.
    func toJSONObject() -> [String: Any] {
        var json: [String: Any] = [
            "callId": callId,
            "callType": callType,
            "displayName": displayName,
            "state": state.stringValue,
            "timestamp": timestamp,
            "wasHeldBySystem": wasHeldBySystem,
            "isMuted": isMuted,
        ]
        if let p = pictureUrl { json["pictureUrl"] = p }
        return json
    }
}

/// An enumeration representing the possible states of a call throughout its lifecycle.
public enum CallState: String {
    /// The call is ringing for the user to answer.
    case incoming = "INCOMING"
    /// An outgoing call has been initiated but not yet started by the system.
    case dialing = "DIALING"
    /// An outgoing call has been started by the system and is waiting for the remote party to answer.
    case connecting = "CONNECTING"
    /// The call is active and media is flowing.
    case active = "ACTIVE"
    /// The call is on hold.
    case held = "HELD"
    /// The call has ended.
    case ended = "ENDED"

    /// A computed property for easy access to the raw string value.
    var stringValue: String { rawValue }
}
