package org.jetlinks.zlmedia.runner.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.zlmedia.ZLMediaOperations;
import org.jetlinks.zlmedia.restful.RestfulZLMediaOperations;
import org.jetlinks.zlmedia.runner.ZLMediaConfigs;
import org.jetlinks.zlmedia.runner.ZLMediaRuntime;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ProcessZLMediaRuntime implements ZLMediaRuntime {

    private final String processFile;

    private Process process;

    private final Sinks.Many<String> output = Sinks
        .many()
        .unicast()
        .onBackpressureBuffer();

    private final Disposable.Composite disposable = Disposables.composite();

    private final Sinks.One<Void> startAwait = Sinks.one();

    private final ZLMediaOperations operations;
    private final Map<String, String> configs = new ConcurrentHashMap<>();
    private final ZLMediaConfigs mediaConfigs;

    //api访问密钥
    private String secret = "zlmedia4jStartup";

    public ProcessZLMediaRuntime(String processFile) {
        this(processFile, new ZLMediaConfigs());
    }

    public ProcessZLMediaRuntime(String processFile, ZLMediaConfigs configs) {
        this(processFile,
             WebClient.builder(),
             new ObjectMapper(),
             configs);
    }

    public ProcessZLMediaRuntime(String processFile,
                                 WebClient.Builder builder,
                                 ObjectMapper mapper,
                                 ZLMediaConfigs configs) {
        this.processFile = processFile;
        this.mediaConfigs = configs;
        this.configs.putAll(configs.createConfigs());
        this.operations = new RestfulZLMediaOperations(
            builder
                .clone()
                .baseUrl("http://127.0.0.1:" + configs.getPorts().getHttp())
                .filter((request, exchange) -> exchange.exchange(
                    ClientRequest
                        .from(request)
                        .url(UriComponentsBuilder
                                 .fromUri(request.url())
                                 .queryParam("secret", secret)
                                 .build()
                                 .toUri())
                        .build()
                ))
                .build(), mapper);
    }

    @Override
    @SneakyThrows
    public Mono<Void> start() {
        return Mono
            //启动
            .fromRunnable(this::start0)
            .subscribeOn(Schedulers.boundedElastic())
            //等待
            .then(startAwait.asMono())
            //保存配置
            .then(saveConfigs());
    }

    private Mono<Void> saveConfigs() {
        String newSecret = UUID.randomUUID().toString().replace("-", "");
        configs.put("api.secret", newSecret);
        return operations
            .opsForState()
            .setConfigs(configs)
            .doOnNext(ignore -> secret = ignore.getOrDefault("api.secret", newSecret))
            .then();
    }

    protected long getPid() {
        try {
            if (process.getClass().getName().equals("java.lang.UNIXProcess")) {
                Field f = process.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                return f.getLong(process);
            }
            return -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    @SneakyThrows
    protected void start0() {
        File file = new File(processFile);

        Path pidFile = Paths.get(processFile + ".pid");
        if (pidFile.toFile().exists()) {
            try {
                String pid = new String(Files.readAllBytes(pidFile));
                log.warn("zlmedia process already exists, kill it:{}", pid);
                Runtime
                    .getRuntime()
                    .exec(new String[]{"kill", pid})
                    .waitFor();
            } catch (Throwable e) {
                log.warn("kill zlmedia process error", e);
            }
        }

        process = new ProcessBuilder()
            .command(file.getAbsolutePath())
            .directory(file.getParentFile())
            .redirectErrorStream(true)
            //.inheritIO()
            .start();
        long pid = getPid();

        if (pid > 0) {
            Files.write(pidFile,
                        String.valueOf(pid).getBytes(),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE);
            pidFile
                .toFile()
                .deleteOnExit();
        }

        //监听进程退出
        disposable
            .add(
                Mono
                    .<DataBuffer>fromCallable(() -> {
                        processExit(process.waitFor());
                        return null;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe()
            );

        //定时检查是否启动成功
        disposable.add(
            Flux.interval(Duration.ofSeconds(2), Duration.ofSeconds(1))
                .onBackpressureDrop()
                .concatMap(ignore -> operations
                    .opsForState()
                    .isAlive())
                .filter(Boolean::booleanValue)
                .take(1)
                .subscribe(ignore -> startAwait.tryEmitEmpty())
        );
    }

    private void handleOutput(String line) {
        output.tryEmitNext(line);
    }

    protected void processExit(int code) {
        if (disposable.isDisposed()) {
            return;
        }
        //启动中...
        if (startAwait.currentSubscriberCount() > 0) {
            startAwait.tryEmitError(new ZLMediaProcessException(code));
        } else {
            log.warn("ZLMediaKit exit with code:{}", code);
        }

        //  disposable.dispose();
    }


    @Override
    public Flux<String> output() {
        return output.asFlux();
    }

    @Override
    public ZLMediaOperations getOperations() {
        return operations;
    }


    @Override
    public void dispose() {
        if (null != process) {
            process.destroy();
        }
    }
}