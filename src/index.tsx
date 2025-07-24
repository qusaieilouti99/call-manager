import { CallManagerHybridObject } from './CallManager.nitro';
import type { CallEventType } from './CallEventType';

// Named exports for only the allowed methods
export const endCall = (callId: string) => {
  CallManagerHybridObject.endCall(callId);
};

export const silenceRingtone = () => {
  CallManagerHybridObject.silenceRingtone();
};

export const getAudioDevices = () => {
  return CallManagerHybridObject.getAudioDevices();
};

export const setAudioRoute = (route: string) => {
  CallManagerHybridObject.setAudioRoute(route);
};

export const keepScreenAwake = (keepAwake: boolean) => {
  CallManagerHybridObject.keepScreenAwake(keepAwake);
};

export const startOutgoingCall = (
  callId: string,
  callType: string,
  targetName: string,
  metadata?: string
) => {
  CallManagerHybridObject.startOutgoingCall(
    callId,
    callType,
    targetName,
    metadata
  );
};

export const startCall = (
  callId: string,
  callType: string,
  targetName: string,
  metadata?: string
) => {
  CallManagerHybridObject.startCall(callId, callType, targetName, metadata);
};

export const endAllCalls = () => {
  CallManagerHybridObject.endAllCalls();
};

export const setOnHold = (callId: string, onHold: boolean) => {
  CallManagerHybridObject.setOnHold(callId, onHold);
};

export const setMuted = (callId: string, muted: boolean) => {
  CallManagerHybridObject.setMuted(callId, muted);
};

export const callAnswered = (callId: string) => {
  CallManagerHybridObject.callAnswered(callId);
};

// Event emitter: addListener returns a remove function
export const addCallManagerListener = (
  listener: (event: CallEventType, payload: string) => void
): (() => void) => CallManagerHybridObject.addListener(listener);

export type { CallEventType };

export default CallManagerHybridObject;
