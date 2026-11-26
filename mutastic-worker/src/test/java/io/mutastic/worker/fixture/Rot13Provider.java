package io.mutastic.worker.fixture;

import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.CipherSpi;
import org.jspecify.annotations.Nullable;

/// A security provider with one cipher, `Rot13`, as a library jar brings one: signed, it stands for
/// a provider a JDK may check the signature of before it hands out its ciphers.
public final class Rot13Provider extends Provider {
  private static final long serialVersionUID = 1L;

  public Rot13Provider() {
    super("MutasticRot13", "1.0", "a cipher that adds 13 to each byte");
    put("Cipher.Rot13", Rot13.class.getName());
  }

  /// Adds 13 to each byte encrypting, takes it away decrypting.
  public static final class Rot13 extends CipherSpi {
    private int shift = 13;

    @Override
    protected void engineSetMode(String mode) {}

    @Override
    protected void engineSetPadding(String padding) {}

    @Override
    protected int engineGetBlockSize() {
      return 1;
    }

    @Override
    protected int engineGetOutputSize(int inputLen) {
      return inputLen;
    }

    @Override
    protected byte[] engineGetIV() {
      return new byte[0];
    }

    @Override
    protected @Nullable AlgorithmParameters engineGetParameters() {
      return null;
    }

    @Override
    protected void engineInit(int opmode, Key key, SecureRandom random) {
      shift = opmode == javax.crypto.Cipher.DECRYPT_MODE ? -13 : 13;
    }

    @Override
    protected void engineInit(
        int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random) {
      engineInit(opmode, key, random);
    }

    @Override
    protected void engineInit(
        int opmode, Key key, AlgorithmParameters params, SecureRandom random) {
      engineInit(opmode, key, random);
    }

    @Override
    protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
      var out = new byte[inputLen];
      for (int at = 0; at < inputLen; at++) {
        out[at] = (byte) (input[inputOffset + at] + shift);
      }
      return out;
    }

    @Override
    protected int engineUpdate(
        byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
      var shifted = engineUpdate(input, inputOffset, inputLen);
      System.arraycopy(shifted, 0, output, outputOffset, shifted.length);
      return shifted.length;
    }

    @Override
    protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen) {
      return engineUpdate(input, inputOffset, inputLen);
    }

    @Override
    protected int engineDoFinal(
        byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
      return engineUpdate(input, inputOffset, inputLen, output, outputOffset);
    }
  }
}
