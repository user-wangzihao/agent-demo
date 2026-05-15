package com.wzh.test;

import java.io.File;
import java.util.Arrays;

public class ClassTreePrinter {

    // 直接使用绝对路径（或者改为相对路径如 "." 或 "src"）
    public static final String SOURCE_ROOT = "D:\\intelligent_design\\agent-showcase";

    public static void main(String[] args) {
        File rootDir = new File(SOURCE_ROOT);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            System.err.println("错误: 找不到源码目录 " + rootDir.getAbsolutePath());
            System.err.println("请检查 SOURCE_ROOT 变量配置是否正确。");
            return;
        }
        System.out.println(rootDir.getName());
        printTree(rootDir, "", true);
    }

    /**
     * 递归打印完整的目录树（不跳过任何文件和空目录）
     *
     * @param dir       当前目录
     * @param prefix    前缀符号（缩进和连线）
     * @param isRootCall 是否为顶层调用（用于控制根目录打印格式，当前未使用但保留）
     */
    private static void printTree(File dir, String prefix, boolean isRootCall) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }

        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            // 空目录也直接返回，没有子项可打印
            return;
        }

        // 排序：目录在前，文件在后，同类型按名称排序
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            boolean isLast = (i == children.length - 1);
            String connector = isLast ? "└── " : "├── ";
            System.out.println(prefix + connector + child.getName());

            if (child.isDirectory()) {
                String newPrefix = prefix + (isLast ? "    " : "│   ");
                printTree(child, newPrefix, false);
            }
        }
    }
}