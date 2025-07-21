import type { HybridObject } from 'react-native-nitro-modules';
import type { CallEventType } from './CallEventType';

export interface CallManager extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // Call control
  endCall(callId: string): void;
  silenceRingtone(): void;

  // Audio/device/screen APIs
  getAudioDevices(): string[];
  setAudioRoute(route: string): void;
  keepScreenAwake(keepAwake: boolean): void;

  // Event emitter: addListener returns a remove function
  addListener(
    listener: (event: CallEventType, payload: string) => void
  ): () => void;
}
