package com.jrasp.module.dependency;

import java.util.Arrays;

// 代码来源于open-rasp
public class Dependency {
    public final String product;
    public final String version;
    public final String vendor;
    public final String path;
    public final String source;

    public Dependency(String product, String version, String vendor, String path, String source) {
        this.product = product;
        this.version = version;
        this.vendor = vendor;
        this.path = path;
        this.source = source;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{this.product, this.version, this.vendor, this.path});
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Dependency dependency = (Dependency) obj;
        if (!product.equals(dependency.product)) return false;
        if (!version.equals(dependency.version)) return false;
        if (!vendor.equals(dependency.vendor)) return false;
        return path.equals(dependency.path);
    }

    @Override
    public String toString() {
        return "Dependency{" +
                "name='" + product + '\'' +
                ", version='" + version + '\'' +
                ", vendor='" + vendor + '\'' +
                ", location='" + path + '\'' +
                '}';
    }
}
