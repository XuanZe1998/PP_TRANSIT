package com.transit.service;
import com.transit.mapper.UserMapper;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DefaultAvatarTests {
 @Test void allowsOnlyBundledAvatarChoices() {
  var mapper=mock(UserMapper.class);
  var service=new UserProfileService(mapper,null,null,null,null,null,null,null);
  var user=User.builder().id(1L).build();
  service.updateBasics(user,Map.of("avatarPath","/avatars/default-4.svg"));
  assertThat(user.getAvatarPath()).isEqualTo("/avatars/default-4.svg");
  verify(mapper).updateById(user);
  clearInvocations(mapper);
  for(var path:java.util.List.of("https://example.com/a.svg","/avatars/../private","/avatars/default-7.svg"))
   assertThatThrownBy(()->service.updateBasics(user,Map.of("avatarPath",path))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  verifyNoInteractions(mapper);
 }
}
