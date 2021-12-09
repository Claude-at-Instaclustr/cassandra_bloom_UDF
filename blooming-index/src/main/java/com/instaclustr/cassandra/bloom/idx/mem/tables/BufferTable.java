package com.instaclustr.cassandra.bloom.idx.mem.tables;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.BitSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

import org.apache.commons.collections4.bloomfilter.BitMapProducer;
import org.apache.commons.collections4.bloomfilter.BloomFilter;
import org.apache.commons.collections4.bloomfilter.Shape;

import com.instaclustr.cassandra.bloom.idx.mem.tables.BusyTable.CloseableIteratorOfInt;

import org.apache.commons.collections4.bloomfilter.BitMap;

public class BufferTable extends AbstractTable implements AutoCloseable {

    /**
     * The number of bits in the bloom filter.
     */
    private final int numberOfBits;

    /**
     * The sizes for a block (Long.SIZE) bloom filters
     */
    //private final int blockBytes;
    private final int blockWords;

    /**
     * Sizes of a bloom filter
     */
    //private final int filterBytes;
    public final int filterWords;


    /**
     * The sizes for a singe bloom filter
     * @param numberOfBits
     * @param bufferFile
     * @throws IOException
     */

    public BufferTable(int numberOfBits, File bufferFile) throws IOException {
        super( bufferFile );

        this.numberOfBits = numberOfBits;

        filterWords = BitMap.numberOfBitMaps( numberOfBits );
        //filterBytes = filterWords * Long.BYTES;

        blockWords = filterWords * Long.BYTES;
        //blockBytes = filterBytes * Long.BYTES;

    }

    @Override
    public String toString() {
        return "BufferTable: "+super.toString();
    }


    private LongBuffer positionBuffer( LongBuffer buffer, int idx ) {
        final int offset = BitMap.getLongIndex(idx) * blockWords;
        buffer.position( offset ).limit(offset+blockWords);
        return buffer;
    }

    /**
     * Checks if the specified index bit is enabled in the array of bit bitmaps.
     *
     * If the bit specified by idx is not in the bitMap false is returned.
     *
     * @param bitMaps  The array of bit maps.
     * @param idx the index of the bit to locate.
     * @return {@code true} if the bit is enabled, {@code false} otherwise.
     */
    public static boolean contains(LongBuffer bitMaps, int idx) {
        return idx >= 0
                && BitMap.getLongIndex(idx) < bitMaps.limit()
                && (bitMaps.get(BitMap.getLongIndex(idx)) & BitMap.getLongBit(idx)) != 0;
    }

    public void setBloomAt(int idx, LongBuffer bloomFilter) throws IOException {
        // extract the proper block
        LongBuffer block = positionBuffer( getWritableLongBuffer(), idx );
        final long mask = BitMap.getLongBit(idx);

        for (int k = 0; k < numberOfBits; k++) {
            long blockData = block.get(k);
            if (contains(bloomFilter, k)) {
                blockData |= mask;
            } else {
                blockData &= ~mask;
            }
            block.put(k);
        }
    }

    public void search(IntConsumer result, LongBuffer bloomFilter, BusyTable busy) throws IOException {
        LongBuffer buffer = getLongBuffer();
        try {
        //Get file channel in read-only mode
            int blockLimit = buffer.remaining() / blockWords;
          for (int bockIdx = 0; bockIdx < blockLimit; bockIdx++) {

              int offset = bockIdx*blockWords;
              long w = ~0l;
              CloseableIteratorOfInt iter = new CloseableIteratorOfInt(bloomFilter);
              while (iter.hasNext()) {
                  w &= buffer.get( offset+iter.next() );
              }

              while (w != 0) {
                  long t = w & -w;
                  int idx = Long.numberOfTrailingZeros(t) + (Long.SIZE * bockIdx);
                  if (busy.isSet(idx)) {
                      result.accept(idx);
                  }
                  w ^= t;
              }
          }
      } finally {
          buffer = null;
      }
    }


}
