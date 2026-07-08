// AudioWorkletProcessor: taps the raw microphone signal at the AudioContext's native
// sample rate and forwards each render quantum (128 samples) to the main thread. All
// resampling/encoding happens on the main thread - this just gets samples off the audio
// rendering thread as cheaply as possible.
class MicProcessor extends AudioWorkletProcessor {

  process(inputs) {
    const channelData = inputs[0] && inputs[0][0];
    if (channelData && channelData.length > 0) {
      this.port.postMessage(channelData.slice(0));
    }
    return true;
  }

}

registerProcessor("mic-processor", MicProcessor);
