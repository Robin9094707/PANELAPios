package eu.simplexsmp.adminsuite;
import java.io.File;
import java.util.*;
/** Pure path resolver: prefer current primary data over legacy and backups. */
final class PlayerDataPaths {
 static List<File> candidates(Collection<File> roots,UUID uuid,String folder,String suffix){
  LinkedHashSet<File> expanded=new LinkedHashSet<>();
  for(File root:roots){expanded.add(root);File walk=root;for(int i=0;i<4&&walk!=null;i++,walk=walk.getParentFile())if(walk.getName().equals("dimensions")&&walk.getParentFile()!=null)expanded.add(walk.getParentFile());}
  String modern=folder.equals("playerdata")?"players/data":"players/"+folder;
  List<File> result=new ArrayList<>();
  for(String path:List.of(modern,folder))for(File root:expanded)result.add(new File(new File(root,path),uuid+suffix));
  if(suffix.equals(".dat"))for(String path:List.of(modern,folder))for(File root:expanded)result.add(new File(new File(root,path),uuid+suffix+"_old"));
  return result;
 }
 static File select(List<File> paths){for(File path:paths)if(path.isFile())return path;return null;}
}
