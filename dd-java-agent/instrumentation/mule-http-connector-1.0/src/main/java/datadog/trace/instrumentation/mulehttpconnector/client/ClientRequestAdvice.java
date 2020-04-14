package datadog.trace.instrumentation.mulehttpconnector.client;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.Request;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.mulehttpconnector.client.ClientDecorator.DECORATE;

// import static
// datadog.trace.instrumentation.mulehttpconnector.HttpRequesterResponseInjectAdapter.SETTER;

public class ClientRequestAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Object source,
      @Advice.Argument(0) final Request request,
      @Advice.Argument(1) final AsyncHandler<?> handler) {

    final AgentSpan span = startSpan("mule.http.client");
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    final String resourceName = request.getMethod() + " " + source.getClass().getName();
    //      propagate().inject(span, request.getHeaders(), SETTER);
    InstrumentationContext.get(AsyncCompletionHandler.class, AgentSpan.class)
        .put((AsyncCompletionHandler) handler, span);
  }
}
