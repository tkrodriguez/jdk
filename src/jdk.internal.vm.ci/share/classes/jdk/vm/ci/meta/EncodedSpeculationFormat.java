package jdk.vm.ci.meta;

import java.util.Arrays;
import java.util.HashMap;

public class EncodedSpeculationFormat {
    static HashMap<Integer, EncodedSpeculationFormat> formats = new HashMap<>();

    final int groupId;
    final String groupName;
    final char[] contextTypes;


    public EncodedSpeculationFormat(int groupId, String groupName, char[] contextTypes) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.contextTypes = contextTypes;
    }

    static char[] typesForContext(Object[] context) {
        char[] types = new char[context.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> c = context[i].getClass();
            if (c.isPrimitive()) {
                types[i] = c.descriptorString().charAt(0);
            } else if (c == String.class) {
                types[i] = 'S';
            } else if (context[i] instanceof ResolvedJavaType) {
                types[i] = 'k';
            } else if (context[i] instanceof ResolvedJavaMethod) {
                types[i] = 'm';
            } else if (context[i] instanceof ResolvedJavaType) {
                types[i] = 'k';
            } else {
                throw new  InternalError();
            }
        }
        return types;
    }

    static synchronized void checkFormat(int groupId, String groupName, Object[] context) {
        EncodedSpeculationFormat format = formats.get(groupId);
        char[] types = typesForContext(context);
        if (format == null) {
            formats.put(groupId, new EncodedSpeculationFormat(groupId, groupName, types));
        } else {
            if (!format.groupName.equals(groupName)) {
                throw new IllegalArgumentException("name mismatch in format: " + groupName + " != " + format.groupName);
            }
            if (!Arrays.equals(format.contextTypes, types)) {
                throw new IllegalArgumentException("type mismatch in format: " + Arrays.toString(format.contextTypes) + " != " + Arrays.toString(types));
            }
        }
    }


}
