package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.component.sbtree;

import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OOperationUnitId;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class OSBTreeRemoveOperation extends OSBTreeOperation {
  private byte[] key;
  private byte[] oldValue;

  @SuppressWarnings("WeakerAccess")
  public OSBTreeRemoveOperation() {
  }

  public OSBTreeRemoveOperation(OOperationUnitId operationUnitId, String name, byte[] key, byte[] oldValue) {
    super(operationUnitId, name);
    this.key = key;
    this.oldValue = oldValue;
  }

  public byte[] getKey() {
    return key;
  }

  public byte[] getOldValue() {
    return oldValue;
  }

  @Override
  public int toStream(byte[] content, int offset) {
    offset = super.toStream(content, offset);

    if (key == null) {
      offset++;
    } else {
      content[offset] = 1;
      offset++;

      OIntegerSerializer.INSTANCE.serializeNative(key.length, content, offset);
      offset += OIntegerSerializer.INT_SIZE;

      System.arraycopy(key, 0, content, offset, key.length);
      offset += key.length;
    }

    OIntegerSerializer.INSTANCE.serializeNative(oldValue.length, content, offset);
    offset += OIntegerSerializer.INT_SIZE;

    System.arraycopy(oldValue, 0, content, offset, oldValue.length);
    offset += oldValue.length;

    return offset;
  }

  @Override
  public int fromStream(byte[] content, int offset) {
    offset = super.fromStream(content, offset);

    if (content[offset] == 0) {
      offset++;
    } else {
      offset++;

      final int keyLen = OIntegerSerializer.INSTANCE.deserializeNative(content, offset);
      offset += OIntegerSerializer.INT_SIZE;

      key = new byte[keyLen];
      System.arraycopy(content, offset, key, 0, keyLen);
      offset += keyLen;
    }

    final int valueLen = OIntegerSerializer.INSTANCE.deserializeNative(content, offset);
    offset += OIntegerSerializer.INT_SIZE;

    oldValue = new byte[valueLen];
    System.arraycopy(content, offset, oldValue, 0, valueLen);
    offset += valueLen;

    return offset;
  }

  @Override
  public void toStream(ByteBuffer buffer) {
    super.toStream(buffer);
    if (key == null) {
      buffer.put((byte) 0);
    } else {
      buffer.put((byte) 1);

      buffer.putInt(key.length);
      buffer.put(key);
    }

    buffer.putInt(oldValue.length);
    buffer.put(oldValue);
  }

  @Override
  public int serializedSize() {
    int size = super.serializedSize();
    size += OByteSerializer.BYTE_SIZE;

    if (key != null) {
      size += OIntegerSerializer.INT_SIZE;
      size += key.length;
    }

    size += OIntegerSerializer.INT_SIZE;
    size += oldValue.length;

    return size;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    if (!super.equals(o))
      return false;
    OSBTreeRemoveOperation that = (OSBTreeRemoveOperation) o;
    return Arrays.equals(key, that.key) && Arrays.equals(oldValue, that.oldValue);
  }

  @Override
  public int hashCode() {

    int result = super.hashCode();
    result = 31 * result + Arrays.hashCode(key);
    result = 31 * result + Arrays.hashCode(oldValue);
    return result;
  }
}