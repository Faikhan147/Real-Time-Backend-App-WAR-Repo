FROM tomcat:10.1-jdk17-temurin
RUN apt-get update && \
    apt-get upgrade -y --fix-missing && \
    apt-get install -y curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
RUN rm -rf /usr/local/tomcat/webapps/*
COPY target/WAR-Project-0.0.1-SNAPSHOT.war /usr/local/tomcat/webapps/ROOT.war
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
CMD curl --fail http://localhost:8080/ || exit 1
CMD ["catalina.sh", "run"]
