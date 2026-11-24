package dev.tomcatdev.hotreload;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Detects structural class changes (fields, methods, signatures, modifiers, super types) that a standard JVM cannot
 * HotSwap. IntelliJ sometimes reports a HotSwap as successful although such a change was not applied
 * (for example a new record component): the running webapp then mixes old and new classes
 * ({@code NoSuchMethodError}). Comparing the compiled class files lets the plugin reload the context instead.
 * <p>
 * Pure Java: reads only the class file header, constant pool, fields and methods (no bytecode library).
 */
public final class ClassShapes {

    private record Entry(long mtime, String shape) {}

    private final Map<Path, Entry> known = new HashMap<>();

    /** Records the current shape of every class under {@code dirs}. */
    public synchronized void snapshot(Collection<Path> dirs) {
        known.clear();
        for (Path f : classFiles(dirs)) {
            try {
                known.put(f, new Entry(Files.getLastModifiedTime(f).toMillis(), shape(f)));
            } catch (Exception ignored) {
                // unreadable file: treated as new next time
            }
        }
    }

    /**
     * Class files changed since the last call (or snapshot) whose shape differs from the previous version.
     * New classes are not structural changes (they are loaded fresh). The snapshot is updated.
     */
    public synchronized List<String> structuralChanges(Collection<Path> dirs) {
        List<String> out = new ArrayList<>();
        for (Path f : classFiles(dirs)) {
            try {
                long mtime = Files.getLastModifiedTime(f).toMillis();
                Entry old = known.get(f);
                if (old != null && old.mtime() == mtime) continue;
                String shape = shape(f);
                if (old != null && !old.shape().equals(shape)) out.add(className(dirs, f));
                known.put(f, new Entry(mtime, shape));
            } catch (Exception ignored) {
                // a class file being written: the next compilation will see it
            }
        }
        return out;
    }

    private static List<Path> classFiles(Collection<Path> dirs) {
        List<Path> out = new ArrayList<>();
        for (Path d : dirs) {
            if (d == null || !Files.isDirectory(d)) continue;
            try (Stream<Path> st = Files.walk(d)) {
                st.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p)).forEach(out::add);
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static String className(Collection<Path> dirs, Path f) {
        for (Path d : dirs) {
            if (d != null && f.startsWith(d)) {
                String rel = d.relativize(f).toString().replace('\\', '/');
                return rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
            }
        }
        return f.getFileName().toString();
    }

    /** "super|interfaces|fields|methods" with members sorted, so member order does not matter. */
    static String shape(Path classFile) throws IOException {
        try (InputStream in = Files.newInputStream(classFile)) {
            return shape(new DataInputStream(new java.io.BufferedInputStream(in)));
        }
    }

    static String shape(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7 -> classNameIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(in.readUnsignedShort() & ~0x0020).append('|');          // class access (ACC_SUPER ignored)
        in.readUnsignedShort();                                            // this class
        int sup = in.readUnsignedShort();
        sb.append(sup == 0 ? "" : utf8[classNameIndex[sup]]).append('|');
        int ifs = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int i = 0; i < ifs; i++) interfaces.add(utf8[classNameIndex[in.readUnsignedShort()]]);
        Collections.sort(interfaces);
        sb.append(interfaces).append('|');
        sb.append(members(in, utf8)).append('|');                          // fields
        sb.append(members(in, utf8));                                      // methods
        return sb.toString();
    }

    private static List<String> members(DataInputStream in, String[] utf8) throws IOException {
        int n = in.readUnsignedShort();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int access = in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String desc = utf8[in.readUnsignedShort()];
            out.add(access + " " + name + desc);
            int attrs = in.readUnsignedShort();
            for (int a = 0; a < attrs; a++) {
                in.readUnsignedShort();
                int len = in.readInt();
                in.skipNBytes(len);
            }
        }
        Collections.sort(out);
        return out;
    }
}
