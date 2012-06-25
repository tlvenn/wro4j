/**
 * Copyright Alex Objelean
 */
package ro.isdc.wro.model.group.processor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.Validate;

import ro.isdc.wro.cache.CacheEntry;
import ro.isdc.wro.cache.CacheStrategy;
import ro.isdc.wro.cache.ContentHashEntry;
import ro.isdc.wro.cache.DefaultSynchronizedCacheStrategyDecorator;
import ro.isdc.wro.cache.impl.LruMemoryCacheStrategy;
import ro.isdc.wro.config.Context;
import ro.isdc.wro.config.DefaultContext;
import ro.isdc.wro.config.jmx.WroConfiguration;
import ro.isdc.wro.manager.WroManager;
import ro.isdc.wro.manager.callback.LifecycleCallbackRegistry;
import ro.isdc.wro.manager.factory.WroManagerFactory;
import ro.isdc.wro.model.WroModel;
import ro.isdc.wro.model.factory.DefaultWroModelFactoryDecorator;
import ro.isdc.wro.model.factory.WroModelFactory;
import ro.isdc.wro.model.group.GroupExtractor;
import ro.isdc.wro.model.resource.locator.factory.InjectorAwareUriLocatorFactoryDecorator;
import ro.isdc.wro.model.resource.locator.factory.SimpleUriLocatorFactory;
import ro.isdc.wro.model.resource.locator.factory.UriLocatorFactory;
import ro.isdc.wro.model.resource.processor.factory.ProcessorsFactory;
import ro.isdc.wro.model.resource.processor.factory.SimpleProcessorsFactory;
import ro.isdc.wro.model.resource.support.ResourceAuthorizationManager;
import ro.isdc.wro.model.resource.support.hash.HashStrategy;
import ro.isdc.wro.model.resource.support.hash.SHA1HashStrategy;
import ro.isdc.wro.model.resource.support.naming.NamingStrategy;
import ro.isdc.wro.model.resource.support.naming.NoOpNamingStrategy;
import ro.isdc.wro.util.ObjectFactory;
import ro.isdc.wro.util.Transformer;


/**
 * Responsible for building the {@link Injector}. It can build an {@link Injector} without needing a {@link WroManager},
 * but just by providing required dependencies.
 * 
 * @author Alex Objelean
 * @since 1.4.3
 * @created 6 Jan 2012
 */
public class InjectorBuilder {
  private final GroupsProcessor groupsProcessor = new GroupsProcessor();
  private final PreProcessorExecutor preProcessorExecutor = new PreProcessorExecutor();
  private UriLocatorFactory uriLocatorFactory = new SimpleUriLocatorFactory();
  private ProcessorsFactory processorsFactory = new SimpleProcessorsFactory();
  private NamingStrategy namingStrategy = new NoOpNamingStrategy();
  private HashStrategy hashStrategy = new SHA1HashStrategy();
  private ResourceAuthorizationManager authorizationManager = new ResourceAuthorizationManager();
  private WroModelFactory modelFactory = null;
  private GroupExtractor groupExtractor = null;
  private LifecycleCallbackRegistry callbackRegistry = null;
  /**
   * A cacheStrategy used for caching processed results.
   */
  private CacheStrategy<CacheEntry, ContentHashEntry> cacheStrategy = new LruMemoryCacheStrategy<CacheEntry, ContentHashEntry>();
  /**
   * A list of model transformers. Allows manager to mutate the model before it is being parsed and processed.
   */
  private List<Transformer<WroModel>> modelTransformers = Collections.emptyList();
  private Injector injector;
  /**
   * Mapping of classes to be annotated and the corresponding injected object. TODO: probably replace this map with
   * something like spring ApplicationContext (lightweight IoC).
   */
  private final Map<Class<?>, Object> map = new HashMap<Class<?>, Object>();
  
  /**
   * Use factory method {@link InjectorBuilder#create(WroManagerFactory)} instead.
   * 
   * @VisibleForTesting
   */
  public InjectorBuilder() {
  }
  
  /**
   * Factory method which uses a managerFactory to initialize injected fields.
   */
  public static InjectorBuilder create(final WroManagerFactory managerFactory) {
    Validate.notNull(managerFactory);
    return new InjectorBuilder(managerFactory.create());
  }
  
  /**
   * Creates an injector from a {@link WroManager}.
   */
  public static InjectorBuilder create(final WroManager manager) {
    Validate.notNull(manager);
    return new InjectorBuilder(manager);
  }
  
  public InjectorBuilder(final WroManager manager) {
    setWroManager(manager);
  }
  
  private void initMap() {
    map.put(PreProcessorExecutor.class, new InjectorObjectFactory<PreProcessorExecutor>() {
      public PreProcessorExecutor create() {
        injector.inject(preProcessorExecutor);
        return preProcessorExecutor;
      }
    });
    map.put(GroupsProcessor.class, new InjectorObjectFactory<GroupsProcessor>() {
      public GroupsProcessor create() {
        injector.inject(groupsProcessor);
        return groupsProcessor;
      }
    });
    map.put(LifecycleCallbackRegistry.class, new InjectorObjectFactory<LifecycleCallbackRegistry>() {
      public LifecycleCallbackRegistry create() {
        injector.inject(callbackRegistry);
        return callbackRegistry;
      }
    });
    map.put(GroupExtractor.class, new InjectorObjectFactory<GroupExtractor>() {
      public GroupExtractor create() {
        injector.inject(groupExtractor);
        return groupExtractor;
      }
    });
    map.put(Injector.class, new InjectorObjectFactory<Injector>() {
      public Injector create() {
        return injector;
      }
    });
    map.put(UriLocatorFactory.class, new InjectorObjectFactory<UriLocatorFactory>() {
      public UriLocatorFactory create() {
        return new InjectorAwareUriLocatorFactoryDecorator(uriLocatorFactory, injector);
      }
    });
    map.put(ProcessorsFactory.class, new InjectorObjectFactory<ProcessorsFactory>() {
      public ProcessorsFactory create() {
        return processorsFactory;
      }
    });
    map.put(WroModelFactory.class, new InjectorObjectFactory<WroModelFactory>() {
      public WroModelFactory create() {
        final WroModelFactory decorated = new DefaultWroModelFactoryDecorator(modelFactory, modelTransformers);
        injector.inject(decorated);
        return decorated;
      }
    });
    map.put(NamingStrategy.class, new InjectorObjectFactory<NamingStrategy>() {
      public NamingStrategy create() {
        injector.inject(namingStrategy);
        return namingStrategy;
      }
    });
    map.put(Context.class, new InjectorObjectFactory<Context>() {
      public Context create() {
        Context proxy = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class[] {
          Context.class
        }, new InvocationHandler() {
          public Object invoke(final Object proxy, final Method method, final Object[] args)
              throws Throwable {
            DefaultContext context = DefaultContext.get();
            return method.invoke(context, args);
          }
        });
        return proxy;
      }
    });
    map.put(WroConfiguration.class, new InjectorObjectFactory<WroConfiguration>() {
      public WroConfiguration create() {
        return DefaultContext.get().getConfig();
      }
    });
    map.put(CacheStrategy.class, new InjectorObjectFactory<CacheStrategy<CacheEntry, ContentHashEntry>>() {
      public CacheStrategy<CacheEntry, ContentHashEntry> create() {
        final CacheStrategy<CacheEntry, ContentHashEntry> decorated = new DefaultSynchronizedCacheStrategyDecorator(
            cacheStrategy);
        injector.inject(decorated);
        return decorated;
      }
    });
    map.put(ResourceAuthorizationManager.class, new InjectorObjectFactory<ResourceAuthorizationManager>() {
      public ResourceAuthorizationManager create() {
        return authorizationManager;
      }
    });
    map.put(HashStrategy.class, new InjectorObjectFactory<HashStrategy>() {
      public HashStrategy create() {
        return hashStrategy;
      }
    });
  }

  public Injector build() {
    // first initialize the map
    initMap();
    return injector = new Injector(Collections.unmodifiableMap(map));
  }
  
  public InjectorBuilder setWroManager(final WroManager manager) {
    Validate.notNull(manager);
    uriLocatorFactory = manager.getUriLocatorFactory();
    processorsFactory = manager.getProcessorsFactory();
    namingStrategy = manager.getNamingStrategy();
    modelFactory = manager.getModelFactory();
    groupExtractor = manager.getGroupExtractor();
    cacheStrategy = manager.getCacheStrategy();
    hashStrategy = manager.getHashStrategy();
    modelTransformers = manager.getModelTransformers();
    callbackRegistry = manager.getCallbackRegistry();
    return this;
  }
  
  public InjectorBuilder setResourceAuthorizationManager(final ResourceAuthorizationManager authManager) {
    Validate.notNull(authManager);
    this.authorizationManager = authManager;
    return this;
  }
  
  /**
   * A special type used for lazy object injection only in context of this class.
   */
  static interface InjectorObjectFactory<T>
      extends ObjectFactory<T> {
  };
}
