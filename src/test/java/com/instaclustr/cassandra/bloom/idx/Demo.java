package com.instaclustr.cassandra.bloom.idx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

import org.apache.commons.collections4.bloomfilter.BitMapProducer;
import org.apache.commons.collections4.bloomfilter.BloomFilter;
import org.apache.commons.collections4.bloomfilter.SimpleBloomFilter;
import org.apache.commons.collections4.bloomfilter.hasher.HasherCollection;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.instaclustr.cassandra.bloom.BulkExecutor;
import com.instaclustr.cassandra.bloom.idx.IdxTable.TokenCapture;
import com.instaclustr.geonames.GeoName;
import com.instaclustr.geonames.GeoNameHasher;
import com.instaclustr.geonames.GeoNameIterator;
import com.instaclustr.geonames.GeoNameLoader;

public class Demo {

    private Cluster cluster;
    private Session session;
    private IdxTable idxTable;

    private static final String keyspace = "CREATE KEYSPACE IF NOT EXISTS geoNames WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };";
    private static final String table = "CREATE TABLE geoNames.geoname (geonameid text, name text, asciiname text, alternatenames text, latitude text, longitude text, feature_class text,feature_code text,country_code text,cc2 text,admin1_code text,admin2_code text, admin3_code text, admin4_code text, population text, elevation text, dem text, timezone text, modification_date text,bf blob,PRIMARY KEY (geonameid ));";


    public Demo() {
        Cluster.Builder builder = Cluster.builder()
                .addContactPoint( "localhost");
        cluster = builder.build();
        session = cluster.connect();
        idxTable = new IdxTable( session,  "geoNames", "geoNamesIdx" );

    }

    public void initTable() {
        session.execute(keyspace);
        session.execute(table);
        idxTable.create();
    }

    public void load( URL url ) throws IOException {
        GeoNameIterator iter = new GeoNameIterator(url);
        GeoNameLoader.load(iter, session, gn -> idxTable.insert( gn.filter, gn.geonameid));
    }

    public List<GeoName> search( BloomFilter filter ) throws InterruptedException {
        List<GeoName> result = new ArrayList<GeoName>();
        BulkExecutor executor = new BulkExecutor( session );
        for (String token : idxTable.search( filter)) {
            executor.execute( GeoName.CassandraSerde.query(token), rs -> rs.forEach( row -> result.add( GeoName.CassandraSerde.deserialize(row))));
        }
        executor.awaitFinish();
        return result;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Demo demo = new Demo();
        System.out.println( "args: "+args.length );
        if (args.length==1)
        {
            demo.initTable();
            demo.load( GeoNameIterator.DEFAULT_INPUT);
        }

        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in)))
        {
            System.out.println( "Enter criteria (enter to quit)");
            String s = reader.readLine();
            HasherCollection hasher = new HasherCollection();


            while ( ! s.isEmpty() ) {
                hasher.add( GeoNameHasher.hasherFor( s ));

                System.out.println( "Enter additional criteria (enter to search)");
                s = reader.readLine();
                while ( ! s.isEmpty() )
                {
                    hasher.add( GeoNameHasher.hasherFor( s ));
                    System.out.println( "Enter additional criteria (enter to search)");
                    s = reader.readLine();
                }

                System.out.println( "\nSearch Results:");
                BloomFilter filter = new SimpleBloomFilter( GeoNameHasher.shape, hasher );
                List<GeoName> results = demo.search( filter );
                results.iterator().forEachRemaining( gn -> System.out.println( String.format( "%s%n%n", gn )));
                System.out.println( "\nEnter criteria (enter to quit)");
                s = reader.readLine();
            }

        }
    }

}
