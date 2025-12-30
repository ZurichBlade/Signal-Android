package org.thoughtcrime.securesms.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import org.signal.core.util.logging.Log;

import java.io.IOException;
import java.io.OutputStream;

import static org.thoughtcrime.securesms.audio.WavCodec.AudioConstants.SAMPLE_RATE;

public class WavCodec implements Recorder {

  private final int         bufferSize;
  private final AudioRecord audioRecord;

  private volatile boolean running = true;
  private          Thread  recordingThread;

  @SuppressLint("MissingPermission")
  public WavCodec() throws IOException {
    this.bufferSize  = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    this.audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                                       AudioFormat.CHANNEL_IN_MONO,
                                       AudioFormat.ENCODING_PCM_16BIT, bufferSize * 10);

    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
      throw new IOException("AudioRecord initialization failed");
    }
  }

  @Override
  public void start(ParcelFileDescriptor fileDescriptor) {
    recordingThread = new Thread(() -> {
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

      try (OutputStream outputStream = new ParcelFileDescriptor.AutoCloseOutputStream(fileDescriptor)) {
        writeWavHeader(outputStream, SAMPLE_RATE, AudioConstants.CHANNELS, AudioConstants.BIT_DEPTH);

        audioRecord.startRecording();

        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
          throw new IOException("AudioRecord failed to start recording state.");
        }

        byte[] audioData = new byte[bufferSize];

        while (running) {
          int read = audioRecord.read(audioData, 0, audioData.length);
          if (read > 0) {
            outputStream.write(audioData, 0, read);
          } else if (read < 0) {
            Log.w("WavCodec", "Error reading audio data: " + read);
            break;
          }
        }
      } catch (IOException e) {
        Log.w("WavCodec", "Recording stopped due to IO error", e);
      } finally {
        try {
          if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.stop();
          }
        } catch (Exception ignored) {}
        audioRecord.release();
      }
    }, "signal-WavCodec");

    recordingThread.start();
  }

  @Override
  public void stop() {
    running = false;
    if (recordingThread != null) {
      try {
        recordingThread.join(500);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void writeWavHeader(OutputStream out, int sampleRate, int channels, int bitsPerSample) throws IOException {
    byte[] header = new byte[44];
    // RIFF header
    header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
    // Use -1 (0xFFFFFFFF) to indicate an unknown/streaming length
    writeInt(header, 4, -1);

    header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';

    // fmt subchunk
    header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
    writeInt(header, 16, 16);
    writeShort(header, 20, (short) 1);
    writeShort(header, 22, (short) channels);
    writeInt(header, 24, sampleRate);
    writeInt(header, 28, sampleRate * channels * bitsPerSample / 8);
    writeShort(header, 32, (short) (channels * bitsPerSample / 8));
    writeShort(header, 34, (short) bitsPerSample);

    // data subchunk
    header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';

    // Again, use -1 for unknown data length
    writeInt(header, 40, -1);
    out.write(header);
  }

  private void writeInt(byte[] canvas, int offset, int value) {
    canvas[offset]     = (byte) (value & 0xff);
    canvas[offset + 1] = (byte) ((value >> 8) & 0xff);
    canvas[offset + 2] = (byte) ((value >> 16) & 0xff);
    canvas[offset + 3] = (byte) ((value >> 24) & 0xff);
  }

  private void writeShort(byte[] canvas, int offset, short value) {
    canvas[offset]     = (byte) (value & 0xff);
    canvas[offset + 1] = (byte) ((value >> 8) & 0xff);
  }

  public static final class AudioConstants {
    public static final int SAMPLE_RATE     = 44100;
    public static final int CHANNELS        = 1;
    public static final int BIT_DEPTH       = 16;
    public static final int WAV_HEADER_SIZE = 44;
    public static final int BAR_COUNT       = 46;

    private AudioConstants() {}
  }

}
