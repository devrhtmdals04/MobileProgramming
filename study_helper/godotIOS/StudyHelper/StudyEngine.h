#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN
/// Owns one Godot instance. It is initialized on first game entry, never on app launch.
@interface StudyEngine : NSObject
@property(nonatomic, readonly) BOOL initialized;
@property(nonatomic, readonly) BOOL rendering;
- (nullable UIViewController *)prepare;
- (void)resume;
- (void)suspend;
@end
NS_ASSUME_NONNULL_END
