package grpc.generated.statsig_forward_proxy;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.62.2)",
    comments = "Source: statsig_forward_proxy.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class StatsigForwardProxyGrpc {

  private StatsigForwardProxyGrpc() {}

  public static final java.lang.String SERVICE_NAME = "statsig_forward_proxy.StatsigForwardProxy";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest,
      grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> getGetConfigSpecMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "getConfigSpec",
      requestType = grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest.class,
      responseType = grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest,
      grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> getGetConfigSpecMethod() {
    io.grpc.MethodDescriptor<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest, grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> getGetConfigSpecMethod;
    if ((getGetConfigSpecMethod = StatsigForwardProxyGrpc.getGetConfigSpecMethod) == null) {
      synchronized (StatsigForwardProxyGrpc.class) {
        if ((getGetConfigSpecMethod = StatsigForwardProxyGrpc.getGetConfigSpecMethod) == null) {
          StatsigForwardProxyGrpc.getGetConfigSpecMethod = getGetConfigSpecMethod =
              io.grpc.MethodDescriptor.<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest, grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "getConfigSpec"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse.getDefaultInstance()))
              .setSchemaDescriptor(new StatsigForwardProxyMethodDescriptorSupplier("getConfigSpec"))
              .build();
        }
      }
    }
    return getGetConfigSpecMethod;
  }

  private static volatile io.grpc.MethodDescriptor<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest,
      grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> getStreamConfigSpecMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamConfigSpec",
      requestType = grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest.class,
      responseType = grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest,
      grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> getStreamConfigSpecMethod() {
    io.grpc.MethodDescriptor<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest, grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> getStreamConfigSpecMethod;
    if ((getStreamConfigSpecMethod = StatsigForwardProxyGrpc.getStreamConfigSpecMethod) == null) {
      synchronized (StatsigForwardProxyGrpc.class) {
        if ((getStreamConfigSpecMethod = StatsigForwardProxyGrpc.getStreamConfigSpecMethod) == null) {
          StatsigForwardProxyGrpc.getStreamConfigSpecMethod = getStreamConfigSpecMethod =
              io.grpc.MethodDescriptor.<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest, grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamConfigSpec"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse.getDefaultInstance()))
              .setSchemaDescriptor(new StatsigForwardProxyMethodDescriptorSupplier("StreamConfigSpec"))
              .build();
        }
      }
    }
    return getStreamConfigSpecMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static StatsigForwardProxyStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<StatsigForwardProxyStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<StatsigForwardProxyStub>() {
        @java.lang.Override
        public StatsigForwardProxyStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new StatsigForwardProxyStub(channel, callOptions);
        }
      };
    return StatsigForwardProxyStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static StatsigForwardProxyBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<StatsigForwardProxyBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<StatsigForwardProxyBlockingStub>() {
        @java.lang.Override
        public StatsigForwardProxyBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new StatsigForwardProxyBlockingStub(channel, callOptions);
        }
      };
    return StatsigForwardProxyBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static StatsigForwardProxyFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<StatsigForwardProxyFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<StatsigForwardProxyFutureStub>() {
        @java.lang.Override
        public StatsigForwardProxyFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new StatsigForwardProxyFutureStub(channel, callOptions);
        }
      };
    return StatsigForwardProxyFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void getConfigSpec(grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest request,
        io.grpc.stub.StreamObserver<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetConfigSpecMethod(), responseObserver);
    }

    /**
     */
    default void streamConfigSpec(grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest request,
        io.grpc.stub.StreamObserver<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStreamConfigSpecMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service StatsigForwardProxy.
   */
  public static abstract class StatsigForwardProxyImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return StatsigForwardProxyGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service StatsigForwardProxy.
   */
  public static final class StatsigForwardProxyStub
      extends io.grpc.stub.AbstractAsyncStub<StatsigForwardProxyStub> {
    private StatsigForwardProxyStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected StatsigForwardProxyStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new StatsigForwardProxyStub(channel, callOptions);
    }

    /**
     */
    public void getConfigSpec(grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest request,
        io.grpc.stub.StreamObserver<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetConfigSpecMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void streamConfigSpec(grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest request,
        io.grpc.stub.StreamObserver<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getStreamConfigSpecMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service StatsigForwardProxy.
   */
  public static final class StatsigForwardProxyBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<StatsigForwardProxyBlockingStub> {
    private StatsigForwardProxyBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected StatsigForwardProxyBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new StatsigForwardProxyBlockingStub(channel, callOptions);
    }

    /**
     */
    public grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse getConfigSpec(grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetConfigSpecMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> streamConfigSpec(
        grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getStreamConfigSpecMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service StatsigForwardProxy.
   */
  public static final class StatsigForwardProxyFutureStub
      extends io.grpc.stub.AbstractFutureStub<StatsigForwardProxyFutureStub> {
    private StatsigForwardProxyFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected StatsigForwardProxyFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new StatsigForwardProxyFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse> getConfigSpec(
        grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetConfigSpecMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_CONFIG_SPEC = 0;
  private static final int METHODID_STREAM_CONFIG_SPEC = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_CONFIG_SPEC:
          serviceImpl.getConfigSpec((grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest) request,
              (io.grpc.stub.StreamObserver<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse>) responseObserver);
          break;
        case METHODID_STREAM_CONFIG_SPEC:
          serviceImpl.streamConfigSpec((grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest) request,
              (io.grpc.stub.StreamObserver<grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetConfigSpecMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest,
              grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse>(
                service, METHODID_GET_CONFIG_SPEC)))
        .addMethod(
          getStreamConfigSpecMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest,
              grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse>(
                service, METHODID_STREAM_CONFIG_SPEC)))
        .build();
  }

  private static abstract class StatsigForwardProxyBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    StatsigForwardProxyBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("StatsigForwardProxy");
    }
  }

  private static final class StatsigForwardProxyFileDescriptorSupplier
      extends StatsigForwardProxyBaseDescriptorSupplier {
    StatsigForwardProxyFileDescriptorSupplier() {}
  }

  private static final class StatsigForwardProxyMethodDescriptorSupplier
      extends StatsigForwardProxyBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    StatsigForwardProxyMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (StatsigForwardProxyGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new StatsigForwardProxyFileDescriptorSupplier())
              .addMethod(getGetConfigSpecMethod())
              .addMethod(getStreamConfigSpecMethod())
              .build();
        }
      }
    }
    return result;
  }
}
