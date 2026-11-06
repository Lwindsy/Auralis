package io.auralis.coredata;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.flywaydb.core.Flyway;
import org.postgresql.PGConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Offline Java-owned admission and import entrypoint; no research SQL write channel. */
public final class CoreDatabase {
    static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final int CHUNK_ROWS = 100_000;
    static final long MAX_LINE = 2_000_000;
    final JsonNode config;
    final JsonNode layout;
    final byte[] layoutBytes;

    CoreDatabase(Path privateConfig) throws Exception {
        config = JSON.readTree(Files.readAllBytes(privateConfig));
        if (!config.path("database").asText().matches("auralis_core(?:_verify|_restore_[0-9a-f]{8})?")) fail("DATABASE_NOT_ALLOWED");
        String expected = "jdbc:postgresql://[^/?#]+/" + config.path("database").asText() + "(?:\\?.*)?";
        if (!config.path("jdbc_url").asText().matches(expected)) fail("CONNECTION_NOT_ALLOWED");
        layoutBytes = resource("core-import-layout.json");
        layout = JSON.readTree(layoutBytes);
    }

    static byte[] resource(String name) throws IOException {
        try (var in = CoreDatabase.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("RESOURCE_MISSING");
            return in.readAllBytes();
        }
    }
    static void fail(String code) { throw new IllegalArgumentException(code); }
    static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    static String sha(Path path) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try(var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024*1024]; int n;
            while ((n=in.read(buffer)) >= 0) hash.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    Connection connect(String role) throws SQLException {
        if(!config.path(role+"_user").asText().equals(config.path("database").asText()+"_"+role))fail("ROLE_NOT_ALLOWED");
        var props = new Properties();
        props.setProperty("user", config.path(role+"_user").asText());
        props.setProperty("password", config.path(role+"_password").asText());
        props.setProperty("connectTimeout","10");
        props.setProperty("ApplicationName","auralis-core-import");
        var c = DriverManager.getConnection(config.path("jdbc_url").asText(), props);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT current_database(),current_user,current_setting('server_version_num')::int")) {
            r.next();
            if (!r.getString(1).equals(config.path("database").asText()) || !r.getString(2).equals(config.path(role+"_user").asText()) || r.getInt(3)/10000!=18) {
                c.close(); throw new SQLException("IDENTITY_MISMATCH");
            }
        }
        try(var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                r.next();if(r.getBoolean(1)){c.close();throw new SQLException("ELEVATED_APPLICATION_ROLE");}
            }
            s.execute("SET statement_timeout='60min'");
            s.execute("SET lock_timeout='30s'");
            s.execute("SET max_parallel_workers_per_gather=0");
        }
        return c;
    }
    void migrate() throws Exception {
        try(var checked=connect("migrator")) { /* Identity and ordinary-role checks precede Flyway. */ }
        Flyway.configure().dataSource(config.path("jdbc_url").asText(),config.path("migrator_user").asText(),config.path("migrator_password").asText())
            .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        String importer = config.path("importer_user").asText();
        String reader = config.path("reader_user").asText();
        if (!importer.equals(config.path("database").asText()+"_importer") || !reader.equals(config.path("database").asText()+"_reader")) fail("ROLE_NOT_ALLOWED");
        try(var c=connect("migrator");var s=c.createStatement()) {
            s.execute("GRANT USAGE ON SCHEMA catalog,statistics,ingestion TO " + importer);
            s.execute("GRANT SELECT,INSERT ON ALL TABLES IN SCHEMA catalog,statistics TO " + importer);
            s.execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA ingestion TO " + importer);
            s.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA ingestion TO " + importer);
            s.execute("GRANT USAGE ON SCHEMA catalog,statistics TO " + reader);
            s.execute("GRANT SELECT ON catalog.current_album,catalog.current_artist,catalog.current_genre,statistics.current_album_listen,statistics.current_artist_listen TO " + reader);
            s.execute("GRANT SELECT ON statistics.current_album_popularity TO " + reader);
            s.execute("GRANT SELECT ON catalog.current_genre_hierarchy_annotation TO " + reader);
            for(var spec:layout.path("tables"))if(spec.path("schema").asText().equals("catalog"))s.execute("GRANT SELECT ON catalog.current_"+spec.path("key").asText()+" TO "+reader);
        }
        System.out.println("CORE_MIGRATION_READY");
    }

    static JsonNode manifest(Path path) throws Exception {
        if (Files.size(path)>2_000_000) fail("MANIFEST_TOO_LARGE");
        var node=JSON.readTree(Files.readAllBytes(path));
        var schema=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(new ByteArrayInputStream(resource("core-import-manifest.schema.json")));
        if (!schema.validate(node).isEmpty()) fail("MANIFEST_SCHEMA_INVALID");
        return node;
    }
    static Path contained(Path root, String name) throws IOException {
        if (!name.matches("[a-z_]+\\.(?:tsv|jsonl\\.gz)")) fail("FILE_NAME_INVALID");
        Path file=root.resolve(name);
        if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root.toRealPath())) fail("FILE_ESCAPES_ROOT");
        return file;
    }
    static String admission(JsonNode manifest, Path policyPath) throws Exception {
        if (Files.size(policyPath)>2_000_000) fail("POLICY_TOO_LARGE");
        byte[] bytes=Files.readAllBytes(policyPath);
        var policy=JSON.readTree(bytes);
        if (!policy.path("enabled").asBoolean(false) || !policy.path("purpose").asText().equals("private-development")) fail("PURPOSE_NOT_ADMITTED");
        if (!policy.path("musicbrainz").equals(manifest.path("sources").path("musicbrainz"))) fail("MB_SOURCE_NOT_ADMITTED");
        var members=manifest.path("sources").path("listenbrainz").path("members");
        if (policy.path("listenbrainz").size()!=members.size()) fail("LB_SOURCE_SET_MISMATCH");
        var fields=members.fields();
        while(fields.hasNext()) {
            var field=fields.next();
            if (!field.getValue().path("sha256").asText().equals(policy.path("listenbrainz").path(field.getKey()).asText())) fail("LB_SOURCE_NOT_ADMITTED");
        }
        return sha(bytes);
    }
    long dataset(Connection c,JsonNode manifest,String hash) throws Exception {
        try(var s=c.prepareStatement("INSERT INTO ingestion.dataset(manifest_sha256,layout_sha256,purpose,manifest,status) VALUES(?,?,?,?::jsonb,'IMPORTING') ON CONFLICT(manifest_sha256) DO NOTHING")) {
            s.setString(1,hash);s.setString(2,manifest.path("layout_sha256").asText());s.setString(3,"private-development");s.setString(4,manifest.toString());s.executeUpdate();
        }
        try(var s=c.prepareStatement("SELECT id,status FROM ingestion.dataset WHERE manifest_sha256=?")) {
            s.setString(1,hash);try(var r=s.executeQuery()){r.next();if(r.getString(2).equals("REJECTED"))fail("DATASET_PREVIOUSLY_REJECTED");return r.getLong(1);}
        }
    }
    void importData(Path root,Path policyPath,String approvedHash,int stopAfterChunks) throws Exception {
        Path mp=root.resolve("manifest.json");
        if (!sha(mp).equals(approvedHash)) fail("MANIFEST_NOT_APPROVED");
        var m=manifest(mp);
        if (!sha(layoutBytes).equals(m.path("layout_sha256").asText())) fail("LAYOUT_MISMATCH");
        String policyHash=admission(m,policyPath);
        Map<String,JsonNode> files=new LinkedHashMap<>();
        for(var file:m.path("files")) if(files.put(file.path("key").asText(),file)!=null) fail("DUPLICATE_FILE_KEY");
        Set<String> expected=new HashSet<>();
        for(var spec:layout.path("tables")) expected.add(spec.path("key").asText());
        if (!expected.equals(files.keySet())) fail("FILE_SET_MISMATCH");
        for(var file:m.path("files")) {
            if (!file.path("path").asText().equals(file.path("key").asText()+".tsv")) fail("FILE_KEY_MISMATCH");
            verifyFile(root,file);
        }
        verifyFile(root,m.path("exceptions"));
        try(var c=connect("importer")) {
            try(var s=c.createStatement();var r=s.executeQuery("SELECT pg_try_advisory_lock(194024)")) {r.next();if(!r.getBoolean(1))fail("IMPORT_ALREADY_RUNNING");}
            c.setAutoCommit(false);
            long id=dataset(c,m,approvedHash); c.commit();
            int chunks=0;
            for(var spec:layout.path("tables")) {
                String key=spec.path("key").asText(); var file=files.get(key);
                try(var s=c.prepareStatement("INSERT INTO ingestion.file_progress(dataset_id,file_key) VALUES(?,?) ON CONFLICT DO NOTHING")) {s.setLong(1,id);s.setString(2,key);s.executeUpdate();} c.commit();
                long committed; boolean complete;
                try(var s=c.prepareStatement("SELECT committed_rows,complete FROM ingestion.file_progress WHERE dataset_id=? AND file_key=?")) {
                    s.setLong(1,id);s.setString(2,key);try(var r=s.executeQuery()){r.next();committed=r.getLong(1);complete=r.getBoolean(2);}
                } c.commit();
                if(complete) {if(committed!=file.path("rows").asLong())fail("PROGRESS_MISMATCH");continue;}
                List<String> columns=new ArrayList<>();columns.add("dataset_id");for(var column:spec.path("columns"))columns.add(column.asText());
                String copy="COPY "+spec.path("schema").asText()+"."+key+"("+String.join(",",columns)+") FROM STDIN WITH(FORMAT text,ENCODING 'UTF8')";
                try(var input=Files.newBufferedReader(contained(root,file.path("path").asText()),StandardCharsets.UTF_8)) {
                    var readHash=MessageDigest.getInstance("SHA-256");
                    long skipped=0;
                    try(var s=c.prepareStatement("SELECT start_row,row_count,sha256 FROM ingestion.import_chunk WHERE dataset_id=? AND file_key=? ORDER BY start_row")) {
                        s.setLong(1,id);s.setString(2,key);
                        try(var chunksRead=s.executeQuery()) {
                            while(chunksRead.next()) {
                                if(chunksRead.getLong(1)!=skipped)fail("CHUNK_SEQUENCE_INVALID");
                                var chunkHash=MessageDigest.getInstance("SHA-256");
                                for(int n=0;n<chunksRead.getInt(2);n++) {
                                    String old=input.readLine();if(old==null)fail("PROGRESS_BEYOND_INPUT");
                                    byte[] bytes=(old+"\n").getBytes(StandardCharsets.UTF_8);chunkHash.update(bytes);readHash.update(bytes);skipped++;
                                }
                                if(!HexFormat.of().formatHex(chunkHash.digest()).equals(chunksRead.getString(3)))fail("COMMITTED_CHUNK_CHANGED");
                            }
                        }
                    }
                    c.commit();if(skipped!=committed)fail("CHECKPOINT_WITHOUT_CHUNKS");
                    boolean eof=false;
                    while(!eof) {
                        if(!admission(m,policyPath).equals(policyHash))fail("ADMISSION_CHANGED_DURING_IMPORT");
                        StringBuilder text=new StringBuilder();int count=0;String line;
                        var chunkHash=MessageDigest.getInstance("SHA-256");long startRow=committed;
                        while(count<CHUNK_ROWS) {
                            line=input.readLine();if(line==null){eof=true;break;}
                            if(line.length()>MAX_LINE || line.split("\t",-1).length!=columns.size()-1)fail("COPY_ROW_INVALID");
                            byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);chunkHash.update(bytes);readHash.update(bytes);
                            text.append(id).append('\t').append(line).append('\n');count++;
                        }
                        if(count>0) {
                            long copied=c.unwrap(PGConnection.class).getCopyAPI().copyIn(copy,new StringReader(text.toString()));
                            if(copied!=count)fail("COPY_COUNT_MISMATCH");committed+=count;
                            try(var s=c.prepareStatement("INSERT INTO ingestion.import_chunk(dataset_id,file_key,start_row,row_count,sha256) VALUES(?,?,?,?,?)")) {
                                s.setLong(1,id);s.setString(2,key);s.setLong(3,startRow);s.setInt(4,count);s.setString(5,HexFormat.of().formatHex(chunkHash.digest()));s.executeUpdate();
                            }
                        }
                        if(eof && !HexFormat.of().formatHex(readHash.digest()).equals(file.path("sha256").asText()))fail("CONSUMED_FILE_DIGEST_MISMATCH");
                        if(committed>file.path("rows").asLong() || (eof && committed!=file.path("rows").asLong()))fail("FILE_ROW_COUNT_MISMATCH");
                        try(var s=c.prepareStatement("UPDATE ingestion.file_progress SET committed_rows=?,complete=? WHERE dataset_id=? AND file_key=?")) {
                            s.setLong(1,committed);s.setBoolean(2,eof);s.setLong(3,id);s.setString(4,key);s.executeUpdate();
                        }
                        c.commit();chunks++;
                        if(chunks%10==0)System.out.println("COPY_PROGRESS "+key+" "+committed);
                        if(stopAfterChunks>0 && chunks>=stopAfterChunks){System.out.println("PARTIAL_AFTER_CHECKPOINT dataset="+id);return;}
                    }
                }
                System.out.println("IMPORTED "+key+" "+committed);
            }
            // Check input bytes again before publication; private staging must remain immutable.
            for(var file:m.path("files"))verifyFile(root,file);
            verifyFile(root,m.path("exceptions"));
            admission(m,policyPath);
            validate(c,id,files);
            try(var s=c.prepareStatement("SELECT status FROM ingestion.dataset WHERE id=? FOR UPDATE")) {
                s.setLong(1,id);try(var r=s.executeQuery()) {
                    r.next();
                    if(r.getString(1).equals("IMPORTING")) {
                        System.out.println("BUILDING_ALBUM_TOTALS dataset="+id);
                        String totals="INSERT INTO statistics.album_popularity SELECT s.dataset_id,o.window_year,s.album_id,count(*),sum(s.listen_count),min(o.last_updated),max(o.last_updated) FROM statistics.album_listen s JOIN statistics.observation o ON (o.dataset_id,o.id)=(s.dataset_id,s.observation_id) WHERE s.dataset_id=? GROUP BY s.dataset_id,o.window_year,s.album_id";
                        try(var aggregate=c.prepareStatement(totals)){aggregate.setLong(1,id);aggregate.executeUpdate();}
                    }
                }
            }
            try(var s=c.prepareStatement("UPDATE ingestion.dataset SET status='VALIDATED' WHERE id=? AND status='IMPORTING'")){s.setLong(1,id);s.executeUpdate();}c.commit();
            System.out.println("CORE_DATA_VALIDATED dataset="+id+" manifest="+approvedHash);
        }
    }
    static void verifyFile(Path root,JsonNode file) throws Exception {
        Path path=contained(root,file.path("path").asText());
        if(Files.size(path)!=file.path("bytes").asLong() || !sha(path).equals(file.path("sha256").asText()))fail("INPUT_FILE_CHANGED");
    }
    void validate(Connection c,long id,Map<String,JsonNode> files) throws Exception {
        for(var spec:layout.path("tables")) {
            String key=spec.path("key").asText();
            try(var s=c.prepareStatement("SELECT count(*) FROM "+spec.path("schema").asText()+"."+key+" WHERE dataset_id=?")) {
                s.setLong(1,id);try(var r=s.executeQuery()){r.next();if(r.getLong(1)!=files.get(key).path("rows").asLong())fail("DATABASE_COUNT_MISMATCH:"+key);}
            }
        }
        String missing="SELECT count(*) FROM catalog.album a WHERE a.dataset_id=? AND (NOT EXISTS(SELECT 1 FROM catalog.album_genre g WHERE g.dataset_id=a.dataset_id AND g.album_id=a.id) OR NOT EXISTS(SELECT 1 FROM catalog.album_credit x WHERE x.dataset_id=a.dataset_id AND x.album_id=a.id))";
        try(var s=c.prepareStatement(missing)){s.setLong(1,id);try(var r=s.executeQuery()){r.next();if(r.getLong(1)!=0)fail("INCOMPLETE_ALBUM");}}
        String cycle="WITH RECURSIVE walk(node,path,cycle) AS (SELECT target_genre,ARRAY[source_genre,target_genre],source_genre=target_genre FROM catalog.genre_relation WHERE dataset_id=? AND relation_type='subgenre' UNION ALL SELECT e.target_genre,w.path||e.target_genre,e.target_genre=ANY(w.path) FROM walk w JOIN catalog.genre_relation e ON e.source_genre=w.node AND e.dataset_id=? AND e.relation_type='subgenre' WHERE NOT w.cycle) SELECT EXISTS(SELECT 1 FROM walk WHERE cycle)";
        try(var s=c.prepareStatement(cycle)){s.setLong(1,id);s.setLong(2,id);try(var r=s.executeQuery()){r.next();if(r.getBoolean(1))fail("SUBGENRE_CYCLE");}}
    }
    void activate(Path root,Path policy,String approvedHash) throws Exception {
        if(!sha(root.resolve("manifest.json")).equals(approvedHash))fail("MANIFEST_NOT_APPROVED");
        var m=manifest(root.resolve("manifest.json"));admission(m,policy);
        verifyFile(root,m.path("exceptions"));
        try(var c=connect("importer")) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()){s.execute("SELECT pg_advisory_xact_lock(194024)");}
            long id;
            try(var s=c.prepareStatement("SELECT id,status FROM ingestion.dataset WHERE manifest_sha256=? FOR UPDATE")) {
                s.setString(1,approvedHash);try(var r=s.executeQuery()){if(!r.next() || !Set.of("VALIDATED","ACTIVE","RETIRED").contains(r.getString(2)))throw new IllegalStateException("DATASET_NOT_VALIDATED");id=r.getLong(1);}
            }
            String policyHash=admission(m,policy);
            try(var s=c.prepareStatement("INSERT INTO ingestion.activation_receipt(dataset_id,admission_sha256) VALUES(?,?)")){s.setLong(1,id);s.setString(2,policyHash);s.executeUpdate();}
            try(var s=c.prepareStatement("UPDATE ingestion.dataset SET status='RETIRED' WHERE status='ACTIVE' AND id<>?")){s.setLong(1,id);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO ingestion.active_dataset(singleton,dataset_id) VALUES(true,?) ON CONFLICT(singleton) DO UPDATE SET dataset_id=excluded.dataset_id")){s.setLong(1,id);s.executeUpdate();}
            try(var s=c.prepareStatement("UPDATE ingestion.dataset SET status='ACTIVE',activated_at=now() WHERE id=?")){s.setLong(1,id);s.executeUpdate();}
            c.commit();System.out.println("CORE_DATA_ACTIVE dataset="+id);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length<2)fail("USAGE: migrate|import|activate private-config [candidate-root admission-policy manifest-sha [stop-after-chunks]]");
        var app=new CoreDatabase(Path.of(args[1]));
        switch(args[0]) {
            case "migrate" -> app.migrate();
            case "import" -> app.importData(Path.of(args[2]),Path.of(args[3]),args[4],args.length>5?Integer.parseInt(args[5]):0);
            case "activate" -> app.activate(Path.of(args[2]),Path.of(args[3]),args[4]);
            default -> fail("UNKNOWN_COMMAND");
        }
    }
}
