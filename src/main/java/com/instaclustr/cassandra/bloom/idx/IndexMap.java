package com.instaclustr.cassandra.bloom.idx;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.WrappedIterator;

import com.instaclustr.cassandra.bloom.idx.std.BFUtils;

/**
 * A Multidimensional Bloom filter entry key.
 */
public class IndexMap {
    /**
     * The byte position in the bloom filter for this code
     */
    private int position;
    /**
     * The code from the position
     */
    private byte[] codes;

    /**
     * The number of bytes the data for the key uses.
     */
    public static final int BYTES = Integer.BYTES+1;

    public IndexMap( IndexKey key ) {
        this( key.getPosition(), BFUtils.byteTable[key.getCode()]);
    }

    /**
     * Constructor.
     * @param position the byte postion of the code in the bloom filter.
     * @param code the code from the filter.
     */
    public IndexMap(int position, byte[] codes ) {
        this.position=position;
        this.codes=codes;
    }

    /**
     * Gets the position of the code for this key.
     * @return the position of the code in the bloom filter.
     */
    public int getPosition() {
        return position;
    }

    /**
     * Gets the code for this key.
     * @return the code from the bloom filter at the position.
     */
    public byte[] getCode() {
        return codes;
    }

    public ExtendedIterator<IndexKey> getKeys() {
        return WrappedIterator.create(new Iterator<IndexKey>() {
            int idx = 0;
            @Override
            public boolean hasNext() {
                return idx<codes.length;
            }

            @Override
            public IndexKey next() {
                if (hasNext()) {
                    return new IndexKey( getPosition(), codes[idx++]);
                }
                throw new NoSuchElementException();
            }

        });
    }


    public interface Consumer extends java.util.function.Consumer<IndexMap> {

    }
}
