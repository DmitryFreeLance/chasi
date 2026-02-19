FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV JAVA_OPTS="-Xms64m -Xmx256m"
RUN mkdir -p /app/data
COPY --from=build /app/target/clock-photo-bot.jar /app/clock-photo-bot.jar
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/clock-photo-bot.jar"]
