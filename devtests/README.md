# Engine tests (no Minecraft needed)
Needs a JDK and a jar containing Gson + slf4j (any fat jar works). From the project root:

    J=path/to/jar-with-gson-and-slf4j.jar
    echo 'package net.fabricmc.api; public interface ModInitializer { void onInitialize(); }' > /tmp/ModInitializer.java   # tiny stub
    javac -cp $J -d out $(find src/main -name '*.java') /tmp/ModInitializer.java
    javac -cp out:$J -d out devtests/*.java
    java -cp out:$J CoreSmokeTest
    java -cp out:$J Milestone2Test
    java -cp out:$J Milestone5Test
