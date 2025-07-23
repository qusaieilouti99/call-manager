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

export const startOutgoingCall = (callId: string, callData: string) => {
  CallManagerHybridObject.startOutgoingCall(callId, callData);
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
