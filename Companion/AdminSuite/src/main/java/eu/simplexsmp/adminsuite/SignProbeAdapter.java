package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.util.*;

/** Mojang's 26.3 sign packets use SignTextSlot and List<String>; 26.2 uses boolean/array. */
final class SignProbeAdapter {
    private SignProbeAdapter() {}
    static Object open(int x,int y,int z)throws ReflectiveOperationException {
        Class<?> pos=Class.forName("net.minecraft.core.BlockPos");
        Object location=pos.getConstructor(int.class,int.class,int.class).newInstance(x,y,z);
        return open(Class.forName("net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket"),pos,location);
    }
    static Object open(Class<?> packet,Class<?> pos,Object location)throws ReflectiveOperationException {
        for(Constructor<?> constructor:packet.getConstructors()){
            Class<?>[] types=constructor.getParameterTypes();
            if(types.length!=2||types[0]!=pos)continue;
            if(types[1]==boolean.class)return constructor.newInstance(location,true);
            if(types[1].isEnum())for(Object slot:types[1].getEnumConstants())
                if(((Enum<?>)slot).name().equals("FRONT"))return constructor.newInstance(location,slot);
        }
        throw new NoSuchMethodException("Unsupported open-sign packet: "+packet.getName());
    }
    static String[] lines(Object packet)throws ReflectiveOperationException {
        Object raw=SuitePacketGuard.call(packet,"getLines","lines");
        if(raw instanceof String[] array)return array.clone();
        if(raw instanceof List<?> list&&list.size()<=4){
            String[] result=new String[list.size()];
            for(int i=0;i<result.length;i++){
                if(!(list.get(i) instanceof String value))throw new IllegalArgumentException("Non-text sign line");
                result[i]=value;
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported sign reply lines");
    }
    static boolean front(Object packet)throws ReflectiveOperationException {
        try{return Boolean.TRUE.equals(SuitePacketGuard.call(packet,"isFrontText","frontText"));}
        catch(NoSuchMethodException modern){Object slot=SuitePacketGuard.call(packet,"slot");return slot instanceof Enum<?> value&&value.name().equals("FRONT");}
    }
}
