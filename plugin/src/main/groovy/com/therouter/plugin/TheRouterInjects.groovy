package com.therouter.plugin

import com.google.gson.Gson
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Created by ZhangTao on 18/2/24.
 */

public class TheRouterInjects {

    // ASM要插入的类，不能包含.class
    public static Map<String, String> serviceProvideMap = new HashMap<>()
    public static Set<String> autowiredSet = new HashSet<>()
    public static Set<String> routeSet = new HashSet<>()

    // 用于编译期代码合法性检查的缓存
    public static final Set<String> routeMapStringSet = new HashSet<>();
    public static final Map<String, String> flowTaskMap = new HashMap<>();
    public static final Set<String> allClass = new HashSet<>();

    public static final Gson gson = new Gson()

    public static final String PREFIX_SERVICE_PROVIDER = "ServiceProvider__TheRouter__"
    public static final String PREFIX_ROUTER_MAP = "RouterMap__TheRouter__"
    public static final String SUFFIX_AUTOWIRED_DOT_CLASS = "__TheRouter__Autowired.class"
    public static final String SUFFIX_AUTOWIRED = "__TheRouter__Autowired"
    public static final String FIELD_FLOW_TASK_JSON = "FLOW_TASK_JSON"
    public static final String FIELD_APT_VERSION = "THEROUTER_APT_VERSION"
    public static final String FIELD_ROUTER_MAP = "ROUTERMAP"
    public static final String FIELD_ROUTER_MAP_COUNT = "COUNT"
    public static final String UNKNOWN_VERSION = "unspecified"
    public static final String NOT_FOUND_VERSION = "0.0.0"
    public static final String DOT_CLASS = ".class"

    public static JarInfo fromCache(File cacheFile) {
        String json = cacheFile.getText("UTF-8")
        JarInfo jarInfo = gson.fromJson(json, JarInfo.class)
        return jarInfo;
    }

    public static void toCache(File cacheFile, JarInfo jarInfo) {
        String json = gson.toJson(jarInfo);
        cacheFile.write(json, "UTF-8")
    }

    /**
     * 标记当前jar中是否有要处理的类，生成类总共三种：RouterMap、ServiceProvider、Autowired
     * @param jarFile
     * @return
     */
    public static JarInfo tagJar(File jarFile, boolean isDebug) {
        JarInfo jarInfo = new JarInfo()
        if (jarFile) {
            def file = new JarFile(jarFile)
            Enumeration enumeration = file.entries()
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement()
                jarInfo.allJarClass.add(jarEntry.name.replaceAll("/", "."))
                if (jarEntry.name.contains(PREFIX_SERVICE_PROVIDER) && !jarEntry.name.contains("\$")) {
                    int start = jarEntry.name.indexOf(PREFIX_SERVICE_PROVIDER)
                    int end = jarEntry.name.length() - DOT_CLASS.length()
                    String className = jarEntry.name.substring(start, end)
                            .replace('\\', '.')
                            .replace('/', '.')
                    if (className.indexOf('$') <= 0) { // 只处理非内部类
                        InputStream inputStream = file.getInputStream(jarEntry)
                        ClassReader reader = new ClassReader(inputStream)
                        ClassNode cn = new ClassNode()
                        reader.accept(cn, 0)
                        List<FieldNode> fieldList = cn.fields
                        String aptVersion = NOT_FOUND_VERSION
                        for (FieldNode fieldNode : fieldList) {
                            if (FIELD_FLOW_TASK_JSON == fieldNode.name) {
                                println("---------TheRouter in jar get flow task json from: ${jarEntry.name}-------------------------------")
                                Map<String, String> map = gson.fromJson(fieldNode.value, HashMap.class);
                                jarInfo.flowTaskMapFromJar.putAll(map)
                            } else if (FIELD_APT_VERSION == fieldNode.name) {
                                aptVersion = fieldNode.value
                            }
                        }
                        if (!serviceProvideMap.containsKey(className) || aptVersion != NOT_FOUND_VERSION) {
                            serviceProvideMap.put(className, aptVersion)
                        }
                    }
                } else if (jarEntry.name.contains("TheRouterServiceProvideInjecter") && !jarEntry.name.contains("\$")) {
                    jarInfo.isTheRouterJar = true;
                    jarInfo.theRouterInjectEntryName = jarEntry.name;
                } else if (jarEntry.name.contains(SUFFIX_AUTOWIRED_DOT_CLASS) && !jarEntry.name.contains("\$")) {
                    String className = jarEntry.name
                            .replace(DOT_CLASS, "")
                            .replace('\\', '.')
                            .replace('/', '.')
                    autowiredSet.add(className)
                } else if (jarEntry.name.contains(PREFIX_ROUTER_MAP) && !jarEntry.name.contains("\$")) {
                    routeSet.add(jarEntry.name)
                    InputStream inputStream = file.getInputStream(jarEntry)
                    ClassReader reader = new ClassReader(inputStream)
                    ClassNode cn = new ClassNode()
                    reader.accept(cn, 0)
                    Map<String, String> fieldMap = new HashMap<>()
                    int count = 0
                    List<FieldNode> fieldList = cn.fields
                    for (FieldNode fieldNode : fieldList) {
                        if (FIELD_ROUTER_MAP_COUNT == fieldNode.name) {
                            count = fieldNode.value
                        }
                        if (fieldNode.name.startsWith(FIELD_ROUTER_MAP)) {
                            fieldMap.put(fieldNode.name, fieldNode.value)
                        }
                    }

                    if (fieldMap.size() == 1 && count == 0) {  // old version
                        fieldMap.values().forEach {
                            println("---------TheRouter in jar get route map from: ${jarEntry.name}-------------------------------")
                            if (isDebug) {
                                println it
                            }
                            jarInfo.routeMapStringFromJar.add(it)
                        }
                    } else if (fieldMap.size() == count) {  // new version
                        StringBuilder stringBuilder = new StringBuilder()
                        for (int i = 0; i < count; i++) {
                            stringBuilder.append(fieldMap.get(FIELD_ROUTER_MAP + i))
                        }
                        println("---------TheRouter in jar get route map from: ${jarEntry.name}-------------------------------")
                        String route = stringBuilder.toString()
                        if (isDebug) {
                            println route
                        }
                        jarInfo.routeMapStringFromJar.add(route)
                    }
                }
            }
        }
        return jarInfo
    }

    /**
     * 本方法仅 Transform API 会用到
     */
    public static SourceInfo tagClass(String path, boolean isDebug) {
        SourceInfo sourceInfo = new SourceInfo();
        File dir = new File(path)
        if (dir.isDirectory()) {
            dir.eachFileRecurse {
                sourceInfo.allSourceClass.add(it.absolutePath.replace(File.separator, "."))
                if (it.absolutePath.contains(PREFIX_SERVICE_PROVIDER) && !it.absolutePath.contains("\$")) {
                    int start = it.absolutePath.indexOf(PREFIX_SERVICE_PROVIDER)
                    int end = it.absolutePath.length() - DOT_CLASS.length()
                    String className = it.absolutePath.substring(start, end)
                            .replace(File.separator, ".")
                    if (className.indexOf('$') > 0) {
                        className = className.substring(0, className.indexOf('$'))
                    }
                    FileInputStream inputStream = new FileInputStream(it)
                    ClassReader reader = new ClassReader(inputStream)
                    ClassNode cn = new ClassNode()
                    reader.accept(cn, 0)
                    List<FieldNode> fieldList = cn.fields
                    String aptVersion = NOT_FOUND_VERSION
                    for (FieldNode fieldNode : fieldList) {
                        if (FIELD_FLOW_TASK_JSON == fieldNode.name) {
                            println("---------TheRouter in source get flow task json from: ${it.name}-------------------------------")
                            Map<String, String> map = gson.fromJson(fieldNode.value, HashMap.class);
                            sourceInfo.flowTaskMapFromSource.putAll(map)
                        } else if (FIELD_APT_VERSION == fieldNode.name) {
                            aptVersion = fieldNode.value
                        }
                    }
                    if (!serviceProvideMap.containsKey(className) || aptVersion != NOT_FOUND_VERSION) {
                        serviceProvideMap.put(className, aptVersion)
                    }
                } else if (it.absolutePath.contains(SUFFIX_AUTOWIRED_DOT_CLASS) && !it.absolutePath.contains("\$")) {
                    String className = it.absolutePath
                            .replace(path, "")
                            .replace(DOT_CLASS, "")
                            .replace(File.separator, ".")
                            .replace("classes.", "")
                    if (className.startsWith(".")) {
                        className = className.substring(1)
                    }
                    autowiredSet.add(className)
                } else if (it.absolutePath.contains(PREFIX_ROUTER_MAP) && !it.absolutePath.contains("\$")) {
                    int start = it.absolutePath.indexOf(PREFIX_ROUTER_MAP)
                    int end = it.absolutePath.length() - DOT_CLASS.length()
                    String className = it.absolutePath.substring(start, end)
                            .replace(File.separator, ".")
                    // 因为absolutePath过滤的时候是直接以类名过滤，就把包名去掉了
                    // 包名一定是a，所以这里补回来
                    routeSet.add("a/" + className)

                    FileInputStream inputStream = new FileInputStream(it)
                    ClassReader reader = new ClassReader(inputStream)
                    ClassNode cn = new ClassNode();
                    reader.accept(cn, 0);

                    Map<String, String> fieldMap = new HashMap<>()
                    int count = 0
                    List<FieldNode> fieldList = cn.fields
                    for (FieldNode fieldNode : fieldList) {
                        if (FIELD_ROUTER_MAP_COUNT == fieldNode.name) {
                            count = fieldNode.value
                        }
                        if (fieldNode.name.startsWith(FIELD_ROUTER_MAP)) {
                            fieldMap.put(fieldNode.name, fieldNode.value)
                        }
                    }

                    if (fieldMap.size() == 1 && count == 0) {  // old version
                        fieldMap.values().forEach { value ->
                            println("---------TheRouter in source get route map from: ${it.name}-------------------------------")
                            if (isDebug) {
                                println value
                            }
                            sourceInfo.routeMapStringFromSource.add(value)
                        }
                    } else if (fieldMap.size() == count) {  // new version
                        StringBuilder stringBuilder = new StringBuilder()
                        for (int i = 0; i < count; i++) {
                            stringBuilder.append(fieldMap.get(FIELD_ROUTER_MAP + i))
                        }
                        println("---------TheRouter in source get route map from: ${it.name}-------------------------------")
                        String route = stringBuilder.toString()
                        if (isDebug) {
                            println route
                        }
                        sourceInfo.routeMapStringFromSource.add(route)
                    }
                }
            }
        }
        return sourceInfo
    }

    /**
     * 开始修改 TheRouterServiceProvideInjecter 类
     */
    static void injectClassCode(File inputJarFile) {
        long start = System.currentTimeMillis()
        def optJarFile = new File(inputJarFile.getParent(), inputJarFile.name + ".opt")
        def inputJar = new JarFile(inputJarFile)
        Enumeration enumeration = inputJar.entries()
        JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(optJarFile))
        while (enumeration.hasMoreElements()) {
            JarEntry jarEntry = (JarEntry) enumeration.nextElement();
            String entryName = jarEntry.getName()
            ZipEntry zipEntry = new ZipEntry(entryName)
            jarOutputStream.putNextEntry(zipEntry)
            InputStream inputStream = inputJar.getInputStream(jarEntry)
            byte[] bytes
            if (entryName.contains("TheRouterServiceProvideInjecter")) {
                ClassReader cr = new ClassReader(inputStream)
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
                AddCodeVisitor cv = new AddCodeVisitor(cw, serviceProvideMap, autowiredSet, routeSet, false)
                cr.accept(cv, ClassReader.SKIP_DEBUG)
                bytes = cw.toByteArray()
            } else {
                bytes = inputStream.getBytes()
            }
            jarOutputStream.write(bytes)
            jarOutputStream.closeEntry()
        }
        jarOutputStream.close()
        inputJar.close()
        inputJarFile.delete()
        optJarFile.renameTo(inputJarFile)
        optJarFile.delete()
        long time = System.currentTimeMillis() - start
        println("---------TheRouter inject TheRouterServiceProvideInjecter.class, spend：${time}ms----------------------")
    }
}
