/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.proxy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;

import com.zaxxer.hikari.ClassLoaderUtils;

/**
 *
 * @author Brett Wooldridge
 */
public class JavassistProxyFactoryFactory
{
    private ClassPool classPool;
    private ProxyFactory proxyFactory;

    public JavassistProxyFactoryFactory()
    {
        ClassPool defaultPool = ClassPool.getDefault();
        classPool = new ClassPool(defaultPool);
        classPool.importPackage("java.sql");
        classPool.childFirstLookup = true;

        try
        {
            generateProxyClass(Connection.class, ConnectionProxy.class);
            generateProxyClass(Statement.class, StatementProxy.class);
            generateProxyClass(CallableStatement.class, CallableStatementProxy.class);
            generateProxyClass(PreparedStatement.class, PreparedStatementProxy.class);
            generateProxyClass(ResultSet.class, ResultSetProxy.class);

            proxyFactory = generateProxyFactory();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public ProxyFactory getProxyFactory()
    {
        return proxyFactory;
    }

    private ProxyFactory generateProxyFactory() throws Exception
    {
        CtClass targetCt = classPool.makeClass("com.zaxxer.hikari.proxy.JavassistProxyFactoryImpl");

        CtClass anInterface = classPool.getCtClass("com.zaxxer.hikari.proxy.ProxyFactory");
        targetCt.addInterface(anInterface);

        for (CtMethod intfMethod : anInterface.getDeclaredMethods())
        {
            CtMethod method = CtNewMethod.copy(intfMethod, targetCt, null);

            StringBuilder call = new StringBuilder("{");
            if ("getProxyConnection".equals(method.getName()))
            {
                call.append("return new com.zaxxer.hikari.proxy.ConnectionJavassistProxy($$);");
            }
            if ("getProxyStatement".equals(method.getName()))
            {
                call.append("return new com.zaxxer.hikari.proxy.StatementJavassistProxy($$);");
            }
            if ("getProxyPreparedStatement".equals(method.getName()))
            {
                call.append("return new com.zaxxer.hikari.proxy.PreparedStatementJavassistProxy($$);");
            }
            if ("getProxyResultSet".equals(method.getName()))
            {
                call.append("return new com.zaxxer.hikari.proxy.ResultSetJavassistProxy($$);");
            }
            if ("getProxyCallableStatement".equals(method.getName()))
            {
                call.append("return new com.zaxxer.hikari.proxy.CallableStatementJavassistProxy($$);");
            }
            call.append('}');
            method.setBody(call.toString());
            targetCt.addMethod(method);
        }

        Class<?> clazz = targetCt.toClass(classPool.getClassLoader(), null);
        return (ProxyFactory) clazz.newInstance();
    }

    /**
     *  Generate Javassist Proxy Classes
     */
    @SuppressWarnings("unchecked")
    private <T> Class<T> generateProxyClass(Class<T> primaryInterface, Class<?> superClass) throws Exception
    {
        // Make a new class that extends one of the JavaProxy classes (ie. superClass); use the name to XxxJavassistProxy instead of XxxProxy
        String superClassName = superClass.getName();
        CtClass superClassCt = classPool.getCtClass(superClassName);
        CtClass targetCt = classPool.makeClass(superClassName.replace("Proxy", "JavassistProxy"), superClassCt);

        // Generate constructors that simply call super(..)
        for (CtConstructor constructor : superClassCt.getConstructors())
        {
            CtConstructor ctConstructor = CtNewConstructor.make(constructor.getParameterTypes(), constructor.getExceptionTypes(), targetCt);
            targetCt.addConstructor(ctConstructor);
        }

        // Make a set of method signatures we inherit implementation for, so we don't generate delegates for these
        Set<String> superSigs = new HashSet<String>();
        for (CtMethod method : superClassCt.getMethods())
        {
            superSigs.add(method.getName() + method.getSignature());
        }

        Set<String> methods = new HashSet<String>();
        Set<Class<?>> interfaces = ClassLoaderUtils.getAllInterfaces(primaryInterface);
        for (Class<?> intf : interfaces)
        {
            CtClass intfCt = classPool.getCtClass(intf.getName());
            targetCt.addInterface(intfCt);
            for (CtMethod intfMethod : intfCt.getDeclaredMethods())
            {
                if (superSigs.contains(intfMethod.getName() + intfMethod.getSignature()))
                {
                    // don't generate delegates for methods we override
                    continue;
                }

                // Ignore already added methods that come from other interfaces
                if (methods.contains(intfMethod.getName() + intfMethod.getSignature()))
                {
                    continue;
                }

                CtMethod method = CtNewMethod.copy(intfMethod, targetCt, null);
                methods.add(intfMethod.getName() + intfMethod.getSignature());

                // Generate a method that simply invokes the same method on the delegate
                String methodBody = "{ try { return ((cast) delegate).method($$); } catch (SQLException e) { throw checkException(e); } }";
                if (method.getReturnType() == CtClass.voidType)
                {
                    methodBody = methodBody.replace("return", "");
                }

                methodBody = methodBody.replace("cast", primaryInterface.getName());
                methodBody = methodBody.replace("method", method.getName());
                method.setBody(methodBody);
                targetCt.addMethod(method);
            }
        }

        return targetCt.toClass(classPool.getClassLoader(), null);
    }
}