package turtest.verticle;

import cloud.tianai.captcha.template.slider.generator.SliderCaptchaGenerator;
import cloud.tianai.captcha.template.slider.generator.common.constant.SliderCaptchaConstant;
import cloud.tianai.captcha.template.slider.generator.common.model.dto.GenerateParam;
import cloud.tianai.captcha.template.slider.generator.common.model.dto.SliderCaptchaInfo;
import cloud.tianai.captcha.template.slider.generator.impl.CacheSliderCaptchaGenerator;
import cloud.tianai.captcha.template.slider.generator.impl.StandardSliderCaptchaGenerator;
import cloud.tianai.captcha.template.slider.resource.ResourceStore;
import cloud.tianai.captcha.template.slider.resource.SliderCaptchaResourceManager;
import cloud.tianai.captcha.template.slider.resource.common.model.dto.Resource;
import cloud.tianai.captcha.template.slider.resource.impl.DefaultSliderCaptchaResourceManager;
import cloud.tianai.captcha.template.slider.resource.impl.provider.ClassPathResourceProvider;
import cloud.tianai.captcha.template.slider.validator.SliderCaptchaValidator;
import cloud.tianai.captcha.template.slider.validator.common.model.dto.SliderCaptchaTrack;
import cloud.tianai.captcha.template.slider.validator.impl.BasicCaptchaTrackValidator;
import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import lombok.extern.slf4j.Slf4j;
import turtest.config.WebProperties;
import turtest.ra.GenCaptchaRa;
import turtest.ra.RedisGetCaptchaRa;
import turtest.ro.Ro;
import turtest.to.RedisGetCaptchaTo;
import turtest.to.RedisSetCaptchaTo;

import java.util.HashMap;
import java.util.Map;

import static cloud.tianai.captcha.template.slider.generator.impl.StandardSliderCaptchaGenerator.DEFAULT_SLIDER_IMAGE_TEMPLATE_PATH;


@Slf4j
public class WebVerticle extends AbstractVerticle {

    // ????????????????????????
    private final SliderCaptchaResourceManager sliderCaptchaResourceManager = new DefaultSliderCaptchaResourceManager();
    // ????????????????????????????????????????????????????????????
    // SliderCaptchaValidator??????????????????????????????????????????????????????????????????????????????; ????????????????????????????????????
    private final SliderCaptchaValidator sliderCaptchaValidator = new BasicCaptchaTrackValidator();
    private SliderCaptchaGenerator sliderCaptchaGenerator;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        initCaptchaGenerator();

        loadCaptchaResource();

        WebProperties webProperties = config().mapTo(WebProperties.class);

        Router router = Router.router(vertx);

        // CORS
        router.route().handler(CorsHandler.create("*").allowedMethod(HttpMethod.GET));

        // ??????????????????????????????
        router.get("/captcha/gen").handler(this::handleCaptchaGen);

        // ???????????????
        router.post("/captcha/verify").handler(BodyHandler.create()).handler(this::handleCaptchaVerify);

        vertx.createHttpServer().requestHandler(router).listen(webProperties.getPort(), res -> {
            if (res.succeeded()) {
                log.info("HTTP server started on port " + res.result().actualPort());
                startPromise.complete();
            } else {
                log.error("HTTP server start fail", res.cause());
                startPromise.fail(res.cause());
            }
        });
    }

    /**
     * ?????????????????????
     *
     * @param ctx ???????????????
     */
    private void handleCaptchaGen(RoutingContext ctx) {
        log.debug("handleCaptchaGen");
        // ??????????????????
        SliderCaptchaInfo slideImageInfo = sliderCaptchaGenerator.generateSlideImageInfo();

        String captchaId = NanoIdUtils.randomNanoId();
        // ??????map??????????????????????????????????????????????????????????????????
        Map<String, Object> map = sliderCaptchaValidator.generateSliderCaptchaValidData(slideImageInfo);

        vertx.eventBus().request(RedisVerticle.EVENT_BUS_REDIS_SET_CAPTCHA, new RedisSetCaptchaTo(captchaId, map), res -> {
            if (res.succeeded()) {
                ctx.response().end(Json.encode(
                        Ro.newSuccess("?????????????????????",
                                new GenCaptchaRa(
                                        captchaId,
                                        slideImageInfo.getBackgroundImage(),
                                        slideImageInfo.getSliderImage()))));

            } else {
                String msg = "?????????????????????";
                log.error(msg, res.cause());
                ctx.response().end(Json.encode(Ro.newFail(msg, res.cause().toString())));
            }
        });
    }

    /**
     * ?????????????????????
     *
     * @param ctx ???????????????
     */
    private void handleCaptchaVerify(RoutingContext ctx) {
        log.debug("handleCaptchaVerify");
        String captchaId = ctx.request().getParam("id");
        SliderCaptchaTrack sliderCaptchaTrack = ctx.getBodyAsJson().mapTo(SliderCaptchaTrack.class);
        if (sliderCaptchaTrack.getTrackList() == null || sliderCaptchaTrack.getTrackList().isEmpty()) {
            Ro<?> ro = Ro.newWarn("?????????????????????");
            String roStr = Json.encode(ro);
            ctx.response().end(roStr);
            return;
        }

        vertx.eventBus().<Ro<?>>request(RedisVerticle.EVENT_BUS_REDIS_GET_CAPTCHA, new RedisGetCaptchaTo(captchaId), res -> {
            Ro<?> ro;
            if (res.succeeded()) {
                Ro<?> redisGetCaptchaRo = res.result().body();
                if (redisGetCaptchaRo.isSuccess()) {
                    Map<String, Object> map = ((Ro<RedisGetCaptchaRa>) redisGetCaptchaRo).getExtra().getMap();

                    // ??????????????????????????????????????????
                    // - sliderCaptchaTrack????????????????????????????????????
                    // - map ??????????????????????????????map??????
                    boolean check = sliderCaptchaValidator.valid(sliderCaptchaTrack, map);

                    ro = check ? Ro.newSuccess("?????????????????????") : Ro.newWarn("?????????????????????");
                } else {
                    ro = Ro.newFail("?????????????????????", redisGetCaptchaRo.getMsg());
                }
            } else {
                ro = Ro.newFail("?????????????????????", "??????Redis??????");
            }
            String roStr = Json.encode(ro);
            ctx.response().end(roStr);
        });
    }


    /**
     * ?????????Captcha?????????
     */
    private void initCaptchaGenerator() {
        // ?????? CacheSliderCaptchaTemplate ???????????????????????????????????????????????????????????????
        // ?????????: ???????????? ????????? SliderCaptchaTemplate
        // ?????????: ???????????????????????????
        // ?????????: ????????? ??????xx?????????????????????
        // ?????????: ??????????????????
        CacheSliderCaptchaGenerator cacheSliderCaptchaGenerator = new CacheSliderCaptchaGenerator(
                new StandardSliderCaptchaGenerator(sliderCaptchaResourceManager, true),
                GenerateParam.builder()
                        .sliderFormatName("webp")
                        .backgroundFormatName("webp")
                        // ????????????????????????
                        .obfuscate(false)
                        .build(),
                10, 1000, 100);
        cacheSliderCaptchaGenerator.initSchedule();
        sliderCaptchaGenerator = cacheSliderCaptchaGenerator;
    }

    /**
     * ??????Captcha??????
     */
    private void loadCaptchaResource() {
        ResourceStore resourceStore = sliderCaptchaResourceManager.getResourceStore();
        // ???????????????????????????
        resourceStore.clearResources();
        // ???????????????????????????
        resourceStore.addResource(new Resource("classpath", "img/bg/01.png"));
        // ??????????????????
        addSliderImage("01", resourceStore);
        addSliderImage("02", resourceStore);
        addSliderImage("03", resourceStore);
        addSliderImage("04", resourceStore);
        addSliderImage("05", resourceStore);
        addSliderImage("06", resourceStore);
        addSliderImage("07", resourceStore);
        addSliderImage("08", resourceStore);
        addSliderImage("09", resourceStore);
        addSliderImage("10", resourceStore);
        addSliderImage("11", resourceStore);
        addSliderImage("12", resourceStore);

    }

    /**
     * ??????????????????
     *
     * @param imageName     ????????????
     * @param resourceStore ?????????
     */
    private void addSliderImage(String imageName, ResourceStore resourceStore) {
        // ????????????
        Map<String, Resource> template = new HashMap<>(4);
        template.put(SliderCaptchaConstant.TEMPLATE_FIXED_IMAGE_NAME, new Resource(ClassPathResourceProvider.NAME, "img/slider/" + imageName + "a.png"));
        template.put(SliderCaptchaConstant.TEMPLATE_ACTIVE_IMAGE_NAME, new Resource(ClassPathResourceProvider.NAME, "img/slider/" + imageName + "b.png"));
        template.put(SliderCaptchaConstant.TEMPLATE_MATRIX_IMAGE_NAME, new Resource(ClassPathResourceProvider.NAME, DEFAULT_SLIDER_IMAGE_TEMPLATE_PATH.concat("/2/matrix.png")));
        resourceStore.addTemplate(template);
    }
}
