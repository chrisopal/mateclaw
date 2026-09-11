import java.sql.*;
/** Offline synthetic source change: exact ID, KB, original text and title guards. */
class ChangeFixtureSource {
 public static void main(String[] a) throws Exception {
  try(var c=DriverManager.getConnection(a[0],"sa","");var p=c.prepareStatement("UPDATE mate_wiki_raw_material SET original_content=? WHERE id=? AND kb_id=? AND original_content=? AND title='T10 合成设备资料' AND deleted=0")) {
   p.setString(1,a[4]);p.setString(2,a[1]);p.setString(3,a[2]);p.setString(4,a[3]);
   if(p.executeUpdate()!=1)throw new IllegalStateException("Synthetic fixture changed or unavailable");
   System.out.println("Changed exactly one guarded synthetic source");
  }
 }
}
