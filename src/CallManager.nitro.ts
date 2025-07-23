// Your CallManager.ts (or similar)
import { type HybridObject, NitroModules } from 'react-native-nitro-modules';
import type { CallEventType } from './CallEventType';

// Define the structure for audio device information
export interface AudioRoutesInfo {
  devices: string[]; // List of available audio device types (e.g., "Speaker", "Earpiece", "Bluetooth", "Headset")
  currentRoute: string; // Currently active audio route (e.g., "Speaker", "Earpiece", "Bluetooth", "Headset", "Unknown")
}

export interface CallManager extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // Call control
  endCall(callId: string): void;
  silenceRingtone(): void;
  // Removed `callType` parameter as it's now expected inside `callData`
  startOutgoingCall(callId: string, callData: string): void;
  callAnswered(callId: string): void; // To signal remote party answered and stop ringback

  // Audio/device/screen APIs
  getAudioDevices(): AudioRoutesInfo; // Return type changed to structured AudioDeviceInfo
  setAudioRoute(route: string): void;
  keepScreenAwake(keepAwake: boolean): void;

  // Event emitter: addListener returns a remove function
  addListener(
    // Payload for AUDIO_DEVICES_CHANGED now matches AudioDeviceInfo structure
    listener: (event: CallEventType, payload: string) => void
  ): () => void;
}


export const CallManagerHybridObject =
  NitroModules.createHybridObject<CallManager>('CallManager');
