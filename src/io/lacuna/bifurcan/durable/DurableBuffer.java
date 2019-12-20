package io.lacuna.bifurcan.durable;

import io.lacuna.bifurcan.DurableInput;
import io.lacuna.bifurcan.DurableOutput;
import io.lacuna.bifurcan.LinearList;
import io.lacuna.bifurcan.durable.allocator.SlabAllocator;
import io.lacuna.bifurcan.durable.allocator.SlabAllocator.SlabBuffer;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class DurableBuffer implements DurableOutput {

  private final LinearList<SlabBuffer> flushed = new LinearList<>();
  private long flushedBytes = 0;

  private SlabBuffer curr;
  private ByteBuffer bytes;

  private boolean isOpen = true;

  private final boolean useCachedAllocator;

  public DurableBuffer() {
    this(true);
  }

  public DurableBuffer(boolean useCachedAllocator) {
    this.useCachedAllocator = useCachedAllocator;
    this.curr = allocate(bufferSize());
    this.bytes = curr.bytes();
  }

  public static void flushTo(DurableOutput out, Consumer<DurableBuffer> body) {
    DurableBuffer acc = new DurableBuffer();
    body.accept(acc);
    acc.flushTo(out);
  }

  public static void flushTo(DurableOutput out, BlockPrefix.BlockType type, Consumer<DurableBuffer> body) {
    DurableBuffer acc = new DurableBuffer();
    body.accept(acc);
    acc.flushTo(out, type);
  }

  /**
   * Writes the contents of the accumulator to `out`, and frees the associated buffers.
   */
  public void flushTo(DurableOutput out) {
    close();
    for (SlabBuffer b : flushed) {
      out.write(b.bytes());
      b.release();
    }
  }

  public DurableInput toInput() {
    close();
    return DurableInput.from(flushed);
  }

  public void flushTo(DurableOutput out, BlockPrefix.BlockType type) {
    close();
    BlockPrefix p = new BlockPrefix(Util.size(flushed), type);
    p.encode(out);
    flushTo(out);
  }

  public void free() {
    close();
    flushed.forEach(SlabBuffer::release);
  }

  @Override
  public void close() {
    if (isOpen) {
      isOpen = false;
      flushedBytes += bytes.position();
      flushed.addLast(curr.trim(bytes.position()));
      bytes = null;
    }
  }

  @Override
  public void flush() {
  }

  @Override
  public long written() {
    return flushedBytes + (bytes != null ? bytes.position() : 0);
  }

  @Override
  public int write(ByteBuffer src) {
    int n = src.remaining();

    Util.transfer(src, bytes);
    while (src.remaining() > 0) {
      Util.transfer(src, ensureCapacity(src.remaining()));
    }

    return n;
  }

  @Override
  public void transferFrom(DurableInput in, long numBytes) {
    while (numBytes > 0) {
      numBytes -= in.read(this.bytes);
      ensureCapacity((int) Math.min(numBytes, Integer.MAX_VALUE));
    }
  }

  @Override
  public void writeByte(int v) {
    ensureCapacity(1).put((byte) v);
  }

  @Override
  public void writeShort(int v) {
    ensureCapacity(2).putShort((short) v);
  }

  @Override
  public void writeChar(int v) {
    ensureCapacity(2).putChar((char) v);
  }

  @Override
  public void writeInt(int v) {
    ensureCapacity(4).putInt(v);
  }

  @Override
  public void writeLong(long v) {
    ensureCapacity(8).putLong(v);
  }

  @Override
  public void writeFloat(float v) {
    ensureCapacity(4).putFloat(v);
  }

  @Override
  public void writeDouble(double v) {
    ensureCapacity(8).putDouble(v);
  }

  //

  private static final int MIN_BUFFER_SIZE = 1 << 10;
  private static final int MAX_BUFFER_SIZE = 64 << 20;

  private int bufferSize() {
    return curr == null ? MIN_BUFFER_SIZE : Math.min(MAX_BUFFER_SIZE, curr.size() * 2);
  }

  private SlabBuffer allocate(int n) {
    return SlabAllocator.allocate(n, useCachedAllocator);
  }

  private ByteBuffer ensureCapacity(int n) {
    if (n > bytes.remaining()) {
      flushedBytes += bytes.position();
      flushed.addLast(curr.trim(bytes.position()));
      curr = allocate(Math.max(bufferSize(), n));
      bytes = curr.bytes();
    }
    return bytes;
  }
}