import { NitroModules } from 'react-native-nitro-modules';
import type { CallManager } from './CallManager.nitro';
import { CallEventType } from './CallEventType';

const CallManagerHybridObject =
  NitroModules.createHybridObject<CallManager>('CallManager');

// Named exports for only the allowed methods
export const endCall = CallManagerHybridObject.endCall;
export const silenceRingtone = CallManagerHybridObject.silenceRingtone;

export const getAudioDevices = CallManagerHybridObject.getAudioDevices;
export const setAudioRoute = CallManagerHybridObject.setAudioRoute;
export const keepScreenAwake = CallManagerHybridObject.keepScreenAwake;

// Event emitter: addListener returns a remove function
export const addCallManagerListener = (
  listener: (event: CallEventType, payload: string) => void
): () => void => CallManagerHybridObject.addListener(listener);

export { CallEventType };
