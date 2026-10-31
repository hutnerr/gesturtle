package com.gesturtle.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Wraps the library's one JDBC connection so a transaction can't be joined
 * by another thread. Most work runs on the DB thread, but some UI code
 * still reads and writes on the FX thread, and a connection has one
 * transaction state for everyone: an FX-thread write made while the DB
 * thread was mid-transaction (an import, a meta/ sync) would become part
 * of it, and vanish if it rolled back.
 *
 * Every call takes the lock for its duration, and setAutoCommit(false)
 * keeps holding it until setAutoCommit(true), so a call from another
 * thread waits for an open transaction to finish instead of joining it.
 * Statements and result sets the connection hands out are wrapped the same
 * way. Uncontended, the lock costs next to nothing.
 */
final class GuardedConnection implements InvocationHandler {
    private final Object target;
    private final State state;

    /** Shared by the connection and everything it hands out. */
    private static final class State {
        final ReentrantLock lock = new ReentrantLock();
        /** Whether the lock holder has a transaction open; only read or written while holding the lock. */
        boolean inTransaction;
    }

    private GuardedConnection(Object target, State state) {
        this.target = target;
        this.state = state;
    }

    static Connection wrap(Connection connection) {
        return (Connection) proxy(connection, Connection.class, new State());
    }

    private static Object proxy(Object target, Class<?> iface, State state) {
        return Proxy.newProxyInstance(GuardedConnection.class.getClassLoader(), new Class<?>[]{iface},
                new GuardedConnection(target, state));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return "Guarded" + target;
        }
        boolean beginsTransaction = false;
        boolean endsTransaction = false;
        if (target instanceof Connection && method.getName().equals("setAutoCommit")) {
            boolean autoCommit = (Boolean) args[0];
            beginsTransaction = !autoCommit;
            endsTransaction = autoCommit;
        }
        state.lock.lock();
        boolean keepHeld = false;
        try {
            Object result = method.invoke(target, args);
            if (beginsTransaction && !state.inTransaction) {
                state.inTransaction = true;
                keepHeld = true;
            } else if (endsTransaction && state.inTransaction) {
                state.inTransaction = false;
                state.lock.unlock(); // the hold taken when the transaction began
            }
            return wrapResult(result, method.getReturnType());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            if (!keepHeld) state.lock.unlock();
        }
    }

    /** Statements, result sets and metadata are wrapped too, since their calls reach the same connection. */
    private Object wrapResult(Object result, Class<?> returnType) {
        if (result == null || !returnType.isInterface() || !returnType.getPackageName().equals("java.sql")) {
            return result;
        }
        return proxy(result, returnType, state);
    }
}
