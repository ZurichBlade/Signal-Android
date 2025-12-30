package org.thoughtcrime.securesms.audio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.media.DecryptableUriMediaInput;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.video.interfaces.MediaInput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

@RequiresApi(api = 23)
public final class AudioWaveFormGenerator {

  private static final String TAG = Log.tag(AudioWaveFormGenerator.class);

  public static final  int BAR_COUNT       = 46;
  private static final int SAMPLES_PER_BAR = 4;

  private AudioWaveFormGenerator() {}

  /**
   * Based on decode sample from:
   * <p>
   * https://android.googlesource.com/platform/cts/+/jb-mr2-release/tests/tests/media/src/android/media/cts/DecoderTest.java
   */
  @WorkerThread
  public static @NonNull AudioFileInfo generateWaveForm(@NonNull Context context, @NonNull Uri uri) throws IOException {

    String mimeType = PartAuthority.getAttachmentContentType(context, uri);

    //using separate fun for wav audio
    if (MediaUtil.AUDIO_WAV.equals(mimeType)) {
      return generateWaveFormForWav(context, uri);
    }

    try (MediaInput dataSource = DecryptableUriMediaInput.createForUri(context, uri)) {
      long[] wave        = new long[BAR_COUNT];
      int[]  waveSamples = new int[BAR_COUNT];

      MediaExtractor extractor = dataSource.createExtractor();

      if (extractor.getTrackCount() == 0) {
        throw new IOException("No audio track");
      }

      MediaFormat format = extractor.getTrackFormat(0);

      if (!format.containsKey(MediaFormat.KEY_DURATION)) {
        throw new IOException("Unknown duration");
      }

      long   totalDurationUs = format.getLong(MediaFormat.KEY_DURATION);
      String mime            = format.getString(MediaFormat.KEY_MIME);

      if (!mime.startsWith("audio/")) {
        throw new IOException("Mime not audio");
      }

      MediaCodec codec = MediaCodec.createDecoderByType(mime);

      if (totalDurationUs == 0) {
        throw new IOException("Zero duration");
      }

      codec.configure(format, null, null, 0);
      codec.start();

      extractor.selectTrack(0);

      long                  kTimeOutUs      = 5000;
      MediaCodec.BufferInfo info            = new MediaCodec.BufferInfo();
      boolean               sawInputEOS     = false;
      boolean               sawOutputEOS    = false;
      int                   noOutputCounter = 0;

      while (!sawOutputEOS && noOutputCounter < 50) {
        noOutputCounter++;
        if (!sawInputEOS) {
          int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);
          if (inputBufIndex >= 0) {
            ByteBuffer dstBuf             = codec.getInputBuffer(inputBufIndex);
            int        sampleSize         = extractor.readSampleData(dstBuf, 0);
            long       presentationTimeUs = 0;

            if (sampleSize < 0) {
              sawInputEOS = true;
              sampleSize  = 0;
            } else {
              presentationTimeUs = extractor.getSampleTime();
            }

            codec.queueInputBuffer(
              inputBufIndex,
              0,
              sampleSize,
              presentationTimeUs,
              sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

            if (!sawInputEOS) {
              int barSampleIndex = (int) (SAMPLES_PER_BAR * (wave.length * extractor.getSampleTime()) / totalDurationUs);
              sawInputEOS = !extractor.advance();
              int nextBarSampleIndex = (int) (SAMPLES_PER_BAR * (wave.length * extractor.getSampleTime()) / totalDurationUs);
              while (!sawInputEOS && nextBarSampleIndex == barSampleIndex) {
                sawInputEOS = !extractor.advance();
                if (!sawInputEOS) {
                  nextBarSampleIndex = (int) (SAMPLES_PER_BAR * (wave.length * extractor.getSampleTime()) / totalDurationUs);
                }
              }
            }
          }
        }

        int outputBufferIndex;
        do {
          outputBufferIndex = codec.dequeueOutputBuffer(info, kTimeOutUs);
          if (outputBufferIndex >= 0) {
            if (info.size > 0) {
              noOutputCounter = 0;
            }

            ByteBuffer buf = codec.getOutputBuffer(outputBufferIndex);
            int barIndex = (int) ((wave.length * info.presentationTimeUs) / totalDurationUs);
            long total = 0;
            for (int i = 0; i < info.size; i += 2 * 4) {
              short aShort = buf.getShort(i);
              total += Math.abs(aShort);
            }
            if (barIndex >= 0 && barIndex < wave.length) {
              wave[barIndex] += total;
              waveSamples[barIndex] += info.size / 2;
            }
            codec.releaseOutputBuffer(outputBufferIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              sawOutputEOS = true;
            }
          } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Log.d(TAG, "output format has changed to " + codec.getOutputFormat());
          }
        } while (outputBufferIndex >= 0);
      }

      codec.stop();
      codec.release();
      extractor.release();

      float[] floats = new float[BAR_COUNT];
      byte[]  bytes  = new byte[BAR_COUNT];
      float   max    = 0;

      for (int i = 0; i < BAR_COUNT; i++) {
        if (waveSamples[i] == 0) continue;

        floats[i] = wave[i] / (float) waveSamples[i];
        if (floats[i] > max) {
          max = floats[i];
        }
      }

      for (int i = 0; i < BAR_COUNT; i++) {
        float normalized = floats[i] / max;
        bytes[i] = (byte) (255 * normalized);
      }

      return new AudioFileInfo(totalDurationUs, bytes);
    }
  }

  @WorkerThread
  private static @NonNull AudioFileInfo generateWaveFormForWav(@NonNull Context context, @NonNull Uri uri) throws IOException {
    try (InputStream inputStream = PartAuthority.getAttachmentStream(context, uri)) {
      if (inputStream == null) throw new IOException("Stream is null");

      // Use shared constant for header size
      long skipped = inputStream.skip(WavCodec.AudioConstants.WAV_HEADER_SIZE);
      if (skipped < WavCodec.AudioConstants.WAV_HEADER_SIZE) {
        throw new IOException("Invalid WAV: Header truncated");
      }

      byte[] allData = StreamUtil.readFully(inputStream);
      if (allData.length == 0) return new AudioFileInfo(0, new byte[WavCodec.AudioConstants.BAR_COUNT]);

      // Convert bytes to shorts efficiently
      ShortBuffer shortBuffer = ByteBuffer.wrap(allData)
                                          .order(ByteOrder.LITTLE_ENDIAN)
                                          .asShortBuffer();

      int     sampleCount = shortBuffer.remaining();
      short[] samples     = new short[sampleCount];
      shortBuffer.get(samples);

      // Calculate duration using shared SAMPLE_RATE
      long totalDurationUs = (sampleCount * 1000000L) / WavCodec.AudioConstants.SAMPLE_RATE;

      float[] barAmplitudes = new float[WavCodec.AudioConstants.BAR_COUNT];
      float   samplesPerBar = (float) sampleCount / WavCodec.AudioConstants.BAR_COUNT;
      float   maxAmplitude  = 0;

      // Process bars
      for (int i = 0; i < WavCodec.AudioConstants.BAR_COUNT; i++) {
        int  start = (int) (i * samplesPerBar);
        int  end   = (int) ((i + 1) * samplesPerBar);
        long sum   = 0;
        int  count = 0;

        for (int j = start; j < end && j < samples.length; j++) {
          sum += Math.abs(samples[j]);
          count++;
        }

        if (count > 0) {
          barAmplitudes[i] = sum / (float) count;
          if (barAmplitudes[i] > maxAmplitude) {
            maxAmplitude = barAmplitudes[i];
          }
        }
      }

      // Normalize to bytes (0-255)
      byte[] finalBars = new byte[WavCodec.AudioConstants.BAR_COUNT];
      for (int i = 0; i < WavCodec.AudioConstants.BAR_COUNT; i++) {
        if (maxAmplitude > 0) {
          finalBars[i] = (byte) ((barAmplitudes[i] / maxAmplitude) * 255);
        }
      }

      return new AudioFileInfo(totalDurationUs, finalBars);
    }
  }

}
