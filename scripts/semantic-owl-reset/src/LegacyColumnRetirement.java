import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Offline retirement of an unused nullable column; never deletes revision rows. */
public final class LegacyColumnRetirement {
    private static final String TABLE="mate_semantic_ontology_revision", COLUMN="definition_json";
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: plan|apply|restore jdbc-url manifest-path; credentials from SEMANTIC_RESET_DB_USER/PASSWORD");
        try(var c=DriverManager.getConnection(args[1],System.getenv().getOrDefault("SEMANTIC_RESET_DB_USER","sa"),System.getenv().getOrDefault("SEMANTIC_RESET_DB_PASSWORD",""))){
            run(c,Path.of(args[2]),args[0]);
        }
    }
    static void run(Connection c,Path file,String mode)throws Exception {
        String product=c.getMetaData().getDatabaseProductName();
        if(!Set.of("H2","MySQL").contains(product))throw new SQLException("UNVERIFIED_DATABASE: retirement supports verified H2/MySQL only");
        if(!Set.of("plan","apply","restore").contains(mode))throw new IllegalArgumentException("Unknown mode");
        String target=hash(c.getMetaData().getURL()+"\n"+c.getMetaData().getUserName()+"\n"+c.getCatalog()+"\n"+c.getSchema());
        boolean present=columnPresent(c);
        if(mode.equals("plan")){
            if(Files.exists(file))throw new IllegalStateException("Manifest already exists");
            if(!present)throw new SQLException("LEGACY_COLUMN_ABSENT");
            verifyLegacyColumn(c);
            var snapshot=LegacyColumnRetirementGuard.inspect(c);
            Properties p=new Properties();p.setProperty("format","legacy-column-retirement-v1");p.setProperty("target",target);
            p.setProperty("product",product);p.setProperty("status","PLANNED");p.setProperty("beforeSchema",schema(c,false));
            p.setProperty("afterSchema",schema(c,true));p.setProperty("documents",snapshot.documentFingerprint());
            p.setProperty("revisions",Long.toString(snapshot.revisions()));
            p.setProperty("legacySqlType",legacySqlType(c));
            save(file,p);System.out.println("Retirement planned; revisions="+snapshot.revisions());return;
        }
        if(!"CONFIRMED_OFFLINE_STOPPED".equals(System.getenv("SEMANTIC_RESET_WRITER_FENCE")))
            throw new IllegalStateException("WRITERS_NOT_STOPPED: stop all semantic writers before DDL");
        Properties p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
        if(!"legacy-column-retirement-v1".equals(p.getProperty("format"))||!target.equals(p.getProperty("target"))||!product.equals(p.getProperty("product")))
            throw new IllegalStateException("MANIFEST_TARGET_MISMATCH");
        var snapshot=LegacyColumnRetirementGuard.inspect(c,!present);
        if(!snapshot.documentFingerprint().equals(p.getProperty("documents"))||!Long.toString(snapshot.revisions()).equals(p.getProperty("revisions")))
            throw new IllegalStateException("DOCUMENTS_CHANGED");
        String expected=p.getProperty(present?"beforeSchema":"afterSchema");
        if(!schema(c,false).equals(expected))throw new IllegalStateException("SCHEMA_CHANGED");
        String status=p.getProperty("status");
        if(mode.equals("apply")){
            if(!Set.of("PLANNED","APPLYING","APPLIED").contains(status))throw new IllegalStateException("INVALID_STATE");
            if(present){
                if(status.equals("APPLIED"))throw new IllegalStateException("COLUMN_REAPPEARED");
                verifyLegacyColumn(c);p.setProperty("status","APPLYING");save(file,p);
                try(var s=c.createStatement()){s.execute("ALTER TABLE "+TABLE+" DROP COLUMN "+COLUMN);}
            }else if(status.equals("PLANNED"))throw new IllegalStateException("COLUMN_REMOVED_OUTSIDE_PLAN");
            if(!schema(c,false).equals(p.getProperty("afterSchema")))throw new IllegalStateException("POST_DDL_SCHEMA_MISMATCH");
            p.setProperty("status","APPLIED");
        }else{
            if(!Set.of("APPLIED","RESTORING","RESTORED").contains(status))throw new IllegalStateException("INVALID_STATE");
            if(!present){
                if(status.equals("RESTORED"))throw new IllegalStateException("COLUMN_DISAPPEARED");
                p.setProperty("status","RESTORING");save(file,p);
                try(var s=c.createStatement()){s.execute("ALTER TABLE "+TABLE+" ADD COLUMN "+COLUMN+" "+restoreSqlType(p,product)+" NULL");}
            }else if(status.equals("APPLIED"))throw new IllegalStateException("COLUMN_RESTORED_OUTSIDE_PLAN");
            if(!schema(c,false).equals(p.getProperty("beforeSchema")))throw new IllegalStateException("RESTORE_SCHEMA_MISMATCH");
            if(!LegacyColumnRetirementGuard.inspect(c).equals(snapshot))throw new IllegalStateException("RESTORE_DATA_MISMATCH");
            p.setProperty("status","RESTORED");
        }
        save(file,p);System.out.println("Retirement state="+p.getProperty("status"));
    }
    static boolean columnPresent(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+TABLE+" WHERE 1=0")){
            for(int i=1;i<=r.getMetaData().getColumnCount();i++)if(COLUMN.equalsIgnoreCase(r.getMetaData().getColumnName(i)))return true;
        }return false;
    }
    private static void verifyLegacyColumn(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT "+COLUMN+" FROM "+TABLE+" WHERE 1=0")){
            var m=r.getMetaData();String type=m.getColumnTypeName(1).toUpperCase(Locale.ROOT);
            if(!(Set.of("CHARACTER LARGE OBJECT","CLOB","LONGTEXT").contains(type)
                    || (c.getMetaData().getDatabaseProductName().equals("H2") && type.equals("CHARACTER VARYING")))
                    ||m.isNullable(1)!=ResultSetMetaData.columnNullable)
                throw new SQLException("UNSUPPORTED_LEGACY_COLUMN_SHAPE");
        }
        // V199 defines a nullable, default-free, unindexed text column. Refuse dependencies.
        String table=actualTable(c);
        try(var r=c.getMetaData().getColumns(c.getCatalog(),c.getSchema(),table,null)){
            while(r.next())if(COLUMN.equalsIgnoreCase(r.getString("COLUMN_NAME"))&&r.getString("COLUMN_DEF")!=null)
                throw new SQLException("LEGACY_COLUMN_HAS_DEFAULT");
        }
        try(var r=c.getMetaData().getIndexInfo(c.getCatalog(),c.getSchema(),table,false,false)){
            while(r.next())if(COLUMN.equalsIgnoreCase(r.getString("COLUMN_NAME")))throw new SQLException("LEGACY_COLUMN_INDEXED");
        }
        String schema=c.getMetaData().getDatabaseProductName().equals("MySQL")?c.getCatalog():c.getSchema();
        try(var p=c.prepareStatement("SELECT cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                +"JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA "
                +"AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=? AND tc.TABLE_NAME=?")){
            p.setString(1,schema);p.setString(2,table);
            try(var r=p.executeQuery()){while(r.next())if(r.getString(1).toLowerCase(Locale.ROOT).contains(COLUMN))
                throw new SQLException("LEGACY_COLUMN_CHECK_CONSTRAINT");}
        }

    }
    private static String legacySqlType(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT "+COLUMN+" FROM "+TABLE+" WHERE 1=0")){
            var m=r.getMetaData();
            if(c.getMetaData().getDatabaseProductName().equals("MySQL"))return "LONGTEXT";
            return m.getColumnTypeName(1).equalsIgnoreCase("CHARACTER VARYING")
                    ? "VARCHAR("+m.getPrecision(1)+")" : "CLOB";
        }
    }
    private static String restoreSqlType(Properties p,String product) {
        String type=p.getProperty("legacySqlType",product.equals("MySQL")?"LONGTEXT":"CLOB");
        boolean valid=product.equals("MySQL")?type.equals("LONGTEXT")
                :type.equals("CLOB")||type.matches("VARCHAR\\([1-9][0-9]{0,9}\\)");
        if(!valid)throw new IllegalStateException("INVALID_LEGACY_SQL_TYPE");
        return type;
    }
    private static String actualTable(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+TABLE+" WHERE 1=0")){return r.getMetaData().getTableName(1);}
    }
    private static String schema(Connection c,boolean omitLegacy)throws Exception {
        List<String> columns=new ArrayList<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+TABLE+" WHERE 1=0")){
            var m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++){
                String name=m.getColumnName(i).toLowerCase(Locale.ROOT);if(omitLegacy&&name.equals(COLUMN))continue;
                columns.add(name+"|"+m.getColumnTypeName(i)+"|"+m.getPrecision(i)+"|"+m.getScale(i)+"|"+m.isNullable(i));
            }
        }Collections.sort(columns);return hash(String.join("\n",columns));
    }
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static void save(Path file,Properties p)throws Exception {
        Path absolute=file.toAbsolutePath();Files.createDirectories(absolute.getParent());
        Path temp=Files.createTempFile(absolute.getParent(),"retirement-",".tmp");
        try{try(var out=Files.newOutputStream(temp)){p.store(out,"No credentials or document content; legacy column is verified all NULL");}
            Files.move(temp,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
}
