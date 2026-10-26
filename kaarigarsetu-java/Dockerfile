# syntax=docker/dockerfile:1
# Build context is the REPOSITORY ROOT (see docker-compose.yml): the image also needs docker/ files.

# ---- build: Maven + JDK 21 compiling for the Java 17 API (maven.compiler.release=17) ----
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build
COPY kaarigarsetu-java/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY kaarigarsetu-java/src ./src
# Tests run in CI (they need a database); the image build only packages.
RUN mvn -B -q -DskipTests package \
 && mkdir /exploded && cd /exploded && jar xf /build/target/kaarigarsetu-java.war

# ---- runtime: Tomcat 10.1 on a JRE, non-root, nothing but the application ----
FROM tomcat:10.1.60-jre17-temurin
RUN rm -rf /usr/local/tomcat/webapps/* \
 && groupadd --system --gid 10001 ks \
 && useradd --system --uid 10001 --gid ks --home-dir /usr/local/tomcat --shell /usr/sbin/nologin ks \
 && mkdir -p /usr/local/tomcat/data /usr/local/tomcat/logs /usr/local/tomcat/temp /usr/local/tomcat/work \
 && chown -R ks:ks /usr/local/tomcat/data /usr/local/tomcat/logs /usr/local/tomcat/temp /usr/local/tomcat/work
# Pre-exploded so the root filesystem can be read-only at run time (no unpacking into webapps/).
COPY --from=build --chown=root:root /exploded /usr/local/tomcat/webapps/ROOT
COPY docker/tomcat/server.xml /usr/local/tomcat/conf/server.xml
COPY docker/app/entrypoint.sh /usr/local/bin/ks-entrypoint.sh
RUN chmod 0555 /usr/local/bin/ks-entrypoint.sh
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Djava.security.egd=file:/dev/urandom -Dfile.encoding=UTF-8"
USER ks
WORKDIR /usr/local/tomcat
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/ks-entrypoint.sh"]
