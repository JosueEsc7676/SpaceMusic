package com.bpm.music.soporte;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Mide cuánto tiempo de cada petición se pasa en la base de datos (y cuántas consultas hace).
 * Envuelve el DataSource para cronometrar las llamadas execute*; no lee ni guarda las consultas.
 * El resultado sale en la cabecera Server-Timing (db) que pone {@link MonitorFiltro}.
 */
@Component
public class TiemposBD implements BeanPostProcessor {

    private static final ThreadLocal<long[]> ACTUAL = new ThreadLocal<>(); // {nanos, consultas}

    public static void iniciar() { ACTUAL.set(new long[2]); }

    public static long[] terminar() {
        long[] v = ACTUAL.get();
        ACTUAL.remove();
        return v == null ? new long[2] : v;
    }

    public static long[] parcial() {
        long[] v = ACTUAL.get();
        return v == null ? new long[2] : v.clone();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String nombre) {
        if (bean instanceof DataSource ds && !(bean instanceof Medido)) return new Medido(ds);
        return bean;
    }

    /** DataSource que devuelve conexiones cronometradas. unwrap/isWrapperFor siguen llegando al pool real. */
    static final class Medido extends DelegatingDataSource {
        Medido(DataSource real) { super(real); }

        @Override
        public Connection getConnection() throws SQLException {
            return conexion(super.getConnection());
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return conexion(super.getConnection(u, p));
        }
    }

    private static Connection conexion(Connection real) {
        return (Connection) Proxy.newProxyInstance(TiemposBD.class.getClassLoader(), new Class<?>[]{Connection.class}, (px, m, args) -> {
            Object r = invocar(real, m, args);
            if (r instanceof CallableStatement cs) return sentencia(cs, CallableStatement.class);
            if (r instanceof PreparedStatement ps) return sentencia(ps, PreparedStatement.class);
            if (r instanceof Statement st) return sentencia(st, Statement.class);
            return r;
        });
    }

    private static Object sentencia(Statement real, Class<?> tipo) {
        return Proxy.newProxyInstance(TiemposBD.class.getClassLoader(), new Class<?>[]{tipo}, (px, m, args) -> {
            if (!m.getName().startsWith("execute")) return invocar(real, m, args);
            long t0 = System.nanoTime();
            try {
                return invocar(real, m, args);
            } finally {
                long[] v = ACTUAL.get();
                if (v != null) { v[0] += System.nanoTime() - t0; v[1]++; }
            }
        });
    }

    private static Object invocar(Object real, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
