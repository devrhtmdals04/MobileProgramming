#import "StudyEngine.h"

// Public Objective-C entry points in the pinned Godot 4.6.2 iOS library.
// Keeping this adapter small avoids linking a second Kotlin runtime or modifying engine sources.
@interface GDTView : UIView
@property(nonatomic, readonly) BOOL canRender;
- (void)startRendering;
- (void)stopRendering;
@end
@interface GDTViewController : UIViewController
@property(nonatomic, readonly, strong) GDTView *godotView;
@end
@interface GDTAppDelegateService : NSObject <UIApplicationDelegate, UIWindowSceneDelegate>
@property(strong, class, nonatomic) GDTViewController *viewController;
@end

@implementation StudyEngine {
    GDTAppDelegateService *_service;
    GDTViewController *_controller;
}
- (BOOL)initialized { return _controller != nil; }
- (BOOL)rendering { return _controller.godotView.canRender; }
- (UIViewController *)prepare {
    if (_controller) return _controller;
    _service = [GDTAppDelegateService new];
    if (![_service application:UIApplication.sharedApplication didFinishLaunchingWithOptions:nil]) return nil;
    _controller = [GDTViewController new];
    GDTAppDelegateService.viewController = _controller;
    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
    [center addObserver:self selector:@selector(willResign:) name:UIApplicationWillResignActiveNotification object:nil];
    [center addObserver:self selector:@selector(didBecome:) name:UIApplicationDidBecomeActiveNotification object:nil];
    [center addObserver:self selector:@selector(didBackground:) name:UIApplicationDidEnterBackgroundNotification object:nil];
    [center addObserver:self selector:@selector(willForeground:) name:UIApplicationWillEnterForegroundNotification object:nil];
    return _controller;
}
- (void)willResign:(NSNotification *)note { [_service applicationWillResignActive:UIApplication.sharedApplication]; }
- (void)didBecome:(NSNotification *)note { if (_controller.view.window) [_service applicationDidBecomeActive:UIApplication.sharedApplication]; }
- (void)didBackground:(NSNotification *)note { [_service applicationDidEnterBackground:UIApplication.sharedApplication]; }
- (void)willForeground:(NSNotification *)note { if (_controller.view.window) [_service applicationWillEnterForeground:UIApplication.sharedApplication]; }
- (void)resume {
    if (!_controller) return;
    [_service applicationWillEnterForeground:UIApplication.sharedApplication];
    [_service applicationDidBecomeActive:UIApplication.sharedApplication];
    [_controller.godotView startRendering];
}
- (void)suspend {
    if (!_controller) return;
    [_service applicationWillResignActive:UIApplication.sharedApplication];
    [_service applicationDidEnterBackground:UIApplication.sharedApplication];
    [_controller.godotView stopRendering];
}
- (void)dealloc { [NSNotificationCenter.defaultCenter removeObserver:self]; }
@end
