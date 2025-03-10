package org.chy.anubis.dynamic.compiler;

import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.chy.anubis.entity.JavaFile;
import org.chy.anubis.exception.CompilerException;
import org.chy.anubis.localcode.LocalCodeManager;
import org.chy.anubis.utils.ListUtils;
import org.chy.anubis.utils.StringUtils;
import org.chy.anubis.utils.WarehouseUtils;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.chy.anubis.Constant.TREASURY_BASE_PATH;

/**
 * 整个anubis项目的动态编译管理器, 使用 compiler() 方法可以动态编译, 将会编译输入文件以及会自动获取依赖文件一起进行编译
 * <p>
 * 使用方法 findClass() 可以获取已经编译好的class文件
 */
public class AnubisCompilerContext {

    private static final JavaCompiler javaCompiler;
    private static final StandardJavaFileManager standardFileManager;
    private AnubisJavaFileManager anubisJavaFileManager;

    static {
        javaCompiler = ToolProvider.getSystemJavaCompiler();
        standardFileManager = javaCompiler.getStandardFileManager(diagnostic -> {
                },
                Locale.CHINESE, StandardCharsets.UTF_8);
        try {
            standardFileManager.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, Collections.emptyList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public AnubisCompilerContext() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.8") || version.startsWith("1.7") ||
                version.startsWith("1.6") || version.startsWith("1.5") || version.startsWith("1.4") || version.startsWith("1.3")) {
            anubisJavaFileManager = new AnubisJavaFileManager(standardFileManager);
        } else {
            anubisJavaFileManager = new AnubisJavaModuleFileManager(standardFileManager);
        }
    }

    public void compiler(List<JavaFile> javaFiles) {
        List<JavaSourceFileObject> javaSourceFileObjects = new ArrayList<>();
        for (JavaFile javaFile : javaFiles) {
            if (anubisJavaFileManager.isExistClass(javaFile.getJavaAllClassName())) {
                continue;
            }
            javaSourceFileObjects.add(new JavaSourceFileObject(javaFile));
        }

        if (javaSourceFileObjects.isEmpty()) {
            return;
        }

        JavaCompiler.CompilationTask task = javaCompiler.getTask(null, anubisJavaFileManager, null, buildOptions(), null,
                javaSourceFileObjects);

        Boolean result = task.call();
        if (!result) {
            String fileMsg = StringUtils.join("[", "]", javaFiles, JavaFile::getName);
            throw new CompilerException("动态编译文件失败 " + fileMsg);
        }
    }

    private Iterable<String> buildOptions() {
        List<String> options = new ArrayList<>();
        options.add("-processor");
        options.add(String.join(",", this.anubisJavaFileManager.findAnnotationProcessor()));
        return options;
    }

    /**
     * 获取编译好的 class文件, 虽然只是 compiler(a.java) 其中 a.java依赖了 c.java 那么同样 findClass可以获取 c.class
     * 该方法同样可以获取 java.lang.String, 但不建议这么做
     *
     * @param path
     * @return
     */

    public byte[] findClass(String path) {
        return doFindClass(path, true);
    }

    @SneakyThrows
    public byte[] doFindClass(String path, boolean loadSource) {
        Optional<JavaFileObject> compilerFile =
                anubisJavaFileManager.getCompilerFile(path);
        //没找到那么可能
        if (!compilerFile.isPresent()) {
            //查找的class对象是 anubis.treasury 包下的,那么开始下载编译
            if (loadSource && path.startsWith(TREASURY_BASE_PATH)) {
                JavaFile javaSource = LocalCodeManager.instance.getJavaSource(WarehouseUtils.javaPathToRemotePath(path));
                //开始编译
                compiler(ListUtils.to(javaSource));
                //编译完再尝试获取一次
                return doFindClass(path, false);
            }

            return null;
        }
        JavaFileObject javaFileObject = compilerFile.get();
        return IOUtils.toByteArray(javaFileObject.openInputStream());

    }


    public boolean isExistClass(String path) {
        return anubisJavaFileManager.isExistClass(path);
    }
}
