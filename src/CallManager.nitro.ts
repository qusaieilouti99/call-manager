// Your CallManager.ts (or similar)
import { type HybridObject, NitroModules } from 'react-native-nitro-modules';
import type { CallEventType } from './CallEventType';

// This is workaround for a swift compiler bug, its preventing us from using string[].
export interface StringHolder {
  value: string;
}

// Define the structure for audio device information
export interface AudioRoutesInfo {
  devices: StringHolder[]; // List of available audio device types (e.g., "Speaker", "Earpiece", "Bluetooth", "Headset")
  currentRoute: string; // Currently active audio route (e.g., "Speaker", "Earpiece", "Bluetooth", "Headset", "Unknown")
}

export interface CallManager
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // Call control
  endCall(callId: string): void;
  silenceRingtone(): void;
  callAnswered(callId: string): void; // To signal remote party answered and stop ringback

  // Audio/device/screen APIs
  getAudioDevices(): AudioRoutesInfo; // Return type changed to structured AudioDeviceInfo
  setAudioRoute(route: string): void;
  keepScreenAwake(keepAwake: boolean): void;

  endAllCalls(): void;
  startOutgoingCall(
    callId: string,
    callType: string,
    targetName: string,
    metadata?: string
  ): void;
  startCall(
    callId: string,
    callType: string,
    targetName: string,
    metadata?: string
  ): void;
  setOnHold(callId: string, onHold: boolean): void;
  setMuted(callId: string, muted: boolean): void;
  updateDisplayCallInformation(callId: string, callerName: string): void;

  // added just new
  reportIncomingCall(
    callId: string,
    callType: string,
    targetName: string,
    metadata?: string,
    token?: string,
    rejectEndpoint?: string
  ): void;

  // Event emitter: addListener returns a remove function
  addListener(
    // Payload for AUDIO_DEVICES_CHANGED now matches AudioDeviceInfo structure
    listener: (event: CallEventType, payload: string) => void
  ): () => void;

  registerVoIPTokenListener(
    // token payload
    listener: (payload: string) => void
  ): () => void;

  hasActiveCall(): boolean; // if there is an active call, no matter ringing, incoming, outgoing, whatever

  requestOverlayPermissionAndroid(): boolean; // only android
  hasOverlayPermissionAndroid(): boolean; // only android
}

export const CallManagerHybridObject =
  NitroModules.createHybridObject<CallManager>('CallManager');
