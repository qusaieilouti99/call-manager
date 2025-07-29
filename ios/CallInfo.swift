import Foundation
import OSLog

public struct CallInfo {
  let callId: String
  let callType: String
  var displayName: String
  let pictureUrl: String?
  var state: CallState
  let timestamp: TimeInterval
  var wasHeldBySystem: Bool
  var isManuallySilenced: Bool

  private static let logger = Logger(subsystem: "com.qusaieilouti99.callmanager",
                                     category: "CallManager")

  init(callId: String,
       callType: String,
       displayName: String,
       pictureUrl: String?,
       state: CallState)
  {
    self.callId = callId
    self.callType = callType
    self.displayName = displayName
    self.pictureUrl = pictureUrl
    self.state = state
    self.timestamp = Date().timeIntervalSince1970
    self.wasHeldBySystem = false
    self.isManuallySilenced = false
    Self.logger.info("CallInfo created: \(callId), \(state.stringValue)")
  }

  mutating func updateState(_ newState: CallState) {
    let old = state
    state = newState
    Self.logger.info("CallInfo  state: \(old.stringValue)→\(newState.stringValue)")
  }

  mutating func updateDisplayName(_ newName: String) {
    let old = displayName
    displayName = newName
      Self.logger.info("CallInfo  name: \(old)→\(newName)")
  }

  func toJSONObject() -> [String: Any] {
    var json: [String: Any] = [
      "callId": callId,
      "callType": callType,
      "displayName": displayName,
      "state": state.stringValue,
      "timestamp": timestamp,
      "wasHeldBySystem": wasHeldBySystem,
      "isManuallySilenced": isManuallySilenced
    ]
    if let p = pictureUrl { json["pictureUrl"] = p }
    Self.logger.debug("CallInfo JSON: \(json)")
    return json
  }
}
public enum CallState: String, CaseIterable {
  case incoming = "INCOMING"
  case dialing  = "DIALING"
  case active   = "ACTIVE"
  case held     = "HELD"
  case ended    = "ENDED"

  var stringValue: String { rawValue }
}
